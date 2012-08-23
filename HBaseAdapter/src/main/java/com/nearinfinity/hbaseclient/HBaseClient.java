package com.nearinfinity.hbaseclient;

import com.nearinfinity.hbaseclient.filter.UUIDFilter;
import com.nearinfinity.hbaseclient.strategy.ScanStrategy;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;

/**
 * Created with IntelliJ IDEA.
 * User: jedstrom
 * Date: 7/25/12
 * Time: 2:09 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBaseClient {
    private HTable table;

    private HBaseAdmin admin;

    private static final byte[] SQL = "sql".getBytes();

    private static final byte[] NIC = "nic".getBytes();

    private static final byte[] IS_DELETED = "isDeleted".getBytes();

    private static final byte[] DELETED_VAL = Bytes.toBytes(1L);

    private static final byte[] UNIREG = "unireg".getBytes();

    private static final byte[] VALUE_COLUMN = "value".getBytes();

    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private static final UUID FULL_UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private final ConcurrentHashMap<String, TableInfo> tableCache = new ConcurrentHashMap<String, TableInfo>();

    private static final Logger logger = Logger.getLogger(HBaseClient.class);

    private long writeBufferSize = 0;

    private long currentBufferSize = 0;

    private long flushCount = 1;

    private long totalFlushTime = 0;

    public HBaseClient(String tableName, String zkQuorum) {
        logger.info("HBaseClient: Constructing with HBase table name: " + tableName);
        logger.info("HBaseClient: Constructing with ZK Quorum: " + zkQuorum);

        Configuration configuration = HBaseConfiguration.create();
        configuration.set("hbase.zookeeper.quorum", zkQuorum);

        try {
            this.admin = new HBaseAdmin(configuration);
            this.initializeSqlTable();

            this.table = new HTable(configuration, tableName);
        } catch (MasterNotRunningException e) {
            logger.error("MasterNotRunningException thrown", e);
        } catch (ZooKeeperConnectionException e) {
            logger.error("ZooKeeperConnectionException thrown", e);
        } catch (IOException e) {
            logger.error("IOException thrown", e);
        } catch (InterruptedException e) {
            logger.error("InterruptedException thrown", e);
        }
    }

    private void initializeSqlTable() throws IOException, InterruptedException {
        HTableDescriptor sqlTableDescriptor;
        HColumnDescriptor nicColumn = new HColumnDescriptor(NIC);

        if (!this.admin.tableExists(SQL)) {
            logger.info("Creating sql table");
            sqlTableDescriptor = new HTableDescriptor(SQL);
            sqlTableDescriptor.addFamily(nicColumn);

            this.admin.createTable(sqlTableDescriptor);
        }

        sqlTableDescriptor = this.admin.getTableDescriptor(SQL);
        if (!sqlTableDescriptor.hasFamily(NIC)) {
            logger.info("Adding nic column family to sql table");

            if (!this.admin.isTableDisabled(SQL)) {
                logger.info("Disabling sql table");
                this.admin.disableTable(SQL);
            }

            this.admin.addColumn(SQL, nicColumn);
        }

        if (this.admin.isTableDisabled(SQL)) {
            logger.info("Enabling sql table");
            this.admin.enableTable(SQL);
        }

        this.admin.flush(SQL);
    }

    private void createTable(String tableName, List<Put> puts) throws IOException {
        //Get and increment the table counter (assumes it exists)
        long tableId = table.incrementColumnValue(RowKeyFactory.ROOT, NIC, new byte[0], 1);

        //Add a row with the table name
        puts.add(new Put(RowKeyFactory.ROOT).add(NIC, tableName.getBytes(), Bytes.toBytes(tableId)));

        //Cache the table
        tableCache.put(tableName, new TableInfo(tableName, tableId));
    }

    private void addColumns(String tableName, Map<String, List<ColumnMetadata>> columns, List<Put> puts) throws IOException {
        //Get table id from cache
        long tableId = tableCache.get(tableName).getId();

        //Build the column row key
        byte[] columnBytes = ByteBuffer.allocate(9).put(RowType.COLUMNS.getValue()).putLong(tableId).array();

        //Allocate ids and compute start id
        long numColumns = columns.size();
        long lastColumnId = table.incrementColumnValue(columnBytes, NIC, new byte[0], numColumns);
        long startColumn = lastColumnId - numColumns;

        for (String columnName : columns.keySet()) {
            long columnId = ++startColumn;

            //Add column
            Put columnPut = new Put(columnBytes).add(NIC, columnName.getBytes(), Bytes.toBytes(columnId));
            puts.add(columnPut);

            // Add column metadata
            byte[] columnInfoBytes = RowKeyFactory.buildColumnInfoKey(tableId, columnId);
            Put columnInfoPut = new Put(columnInfoBytes);

            for (ColumnMetadata meta : columns.get(columnName)) {
                columnInfoPut.add(NIC, meta.getValue(), columnName.getBytes());
            }

            puts.add(columnInfoPut);

            //Add to cache
            tableCache.get(tableName).addColumn(columnName, columnId, columns.get(columnName));
        }
    }

    public void createTableFull(String tableName, Map<String, List<ColumnMetadata>> columns) throws IOException {
        //Batch put list
        List<Put> putList = new LinkedList<Put>();

        //Create table and add to put list
        createTable(tableName, putList);

        //Create columns and add to put list
        addColumns(tableName, columns, putList);

        //Perform all puts
        table.put(putList);

        table.flushCommits();
    }

    public void writeRow(String tableName, Map<String, byte[]> values) throws IOException {
        writeRow(tableName, values, null);
    }

    public void writeRow(String tableName, Map<String, byte[]> values, byte[] unireg) throws IOException {
        //Get table id
        TableInfo info = getTableInfo(tableName);
        long tableId = info.getId();

        //Get UUID for new entry
        UUID rowId = UUID.randomUUID();

        //Build data row key
        byte[] dataKey = RowKeyFactory.buildDataKey(tableId, rowId);

        //Create put list
        List<Put> putList = new LinkedList<Put>();

        Put dataRow = new Put(dataKey);

        byte[] indexQualifier = new byte[0];
        byte[] indexValue = new byte[0];
        if (unireg != null) {
            indexQualifier = UNIREG;
            indexValue = unireg;
        }

        boolean allRowsNull = true;

        for (String columnName : values.keySet()) {

            //Get column id and value
            long columnId = info.getColumnIdByName(columnName);
            ColumnMetadata columnType = info.getColumnTypeByName(columnName);
            byte[] value = values.get(columnName);


            if (value == null) {
                // Build null index
                byte[] nullIndexRow = RowKeyFactory.buildNullIndexKey(tableId, columnId, rowId);
                putList.add(new Put(nullIndexRow).add(NIC, indexQualifier, indexValue));
            } else {
                allRowsNull = false;
                // Add data column to put
                dataRow.add(NIC, Bytes.toBytes(columnId), value);

                // Build value index key
                byte[] indexRow = RowKeyFactory.buildValueIndexKey(tableId, columnId, value, rowId);
                putList.add(new Put(indexRow).add(NIC, indexQualifier, indexValue));

                // Build secondary index key
                byte[] secondaryIndexRow = RowKeyFactory.buildSecondaryIndexKey(tableId, columnId, value, columnType);
                putList.add(new Put(secondaryIndexRow).add(NIC, VALUE_COLUMN, value));

                // Build reverse index key
                byte[] reverseIndexRow = RowKeyFactory.buildReverseIndexKey(tableId, columnId, value, columnType);
                putList.add(new Put(reverseIndexRow).add(NIC, VALUE_COLUMN, value));
            }
        }

        if (allRowsNull) {
            // Add special []->[] data row to signify a row of all null values
            putList.add(dataRow.add(NIC, new byte[0], new byte[0]));
        }

        //Add the row to put list
        putList.add(dataRow);
        for (Put p : putList) {
            this.currentBufferSize += p.heapSize();
        }

        if (this.currentBufferSize >= this.writeBufferSize) {
            long start = System.currentTimeMillis();
            table.put(putList);
            long elapsed = System.currentTimeMillis() - start;
            this.totalFlushTime += elapsed;
            logger.info(format("Flush # %d, Timing %d ms, Average %d ms, Total %d ms", this.flushCount, elapsed, this.totalFlushTime / this.flushCount, this.totalFlushTime));
            this.currentBufferSize = 0;
            this.flushCount++;
        } else {
            //Final put
            table.put(putList);
        }
    }

    public Result getDataRow(UUID uuid, String tableName) throws IOException {
        TableInfo info = getTableInfo(tableName);
        long tableId = info.getId();

        byte[] rowKey = RowKeyFactory.buildDataKey(tableId, uuid);

        Get get = new Get(rowKey);
        return table.get(get);
    }

    private TableInfo getTableInfo(String tableName) throws IOException {
        if (tableCache.containsKey(tableName)) {
            return tableCache.get(tableName);
        }

        //Get the table id from HBase
        Get tableIdGet = new Get(RowKeyFactory.ROOT);
        Result result = table.get(tableIdGet);
        long tableId = ByteBuffer.wrap(result.getValue(NIC, tableName.getBytes())).getLong();

        TableInfo info = new TableInfo(tableName, tableId);

        byte[] rowKey = RowKeyFactory.buildColumnsKey(tableId);

        Get columnsGet = new Get(rowKey);
        Result columnsResult = table.get(columnsGet);
        Map<byte[], byte[]> columns = columnsResult.getFamilyMap(NIC);
        for (byte[] qualifier : columns.keySet()) {
            String columnName = new String(qualifier);
            long columnId = ByteBuffer.wrap(columns.get(qualifier)).getLong();
            info.addColumn(columnName, columnId, getMetadataForColumn(tableId, columnId));
        }

        return info;
    }

    public List<ColumnMetadata> getMetadataForColumn(long tableId, long columnId) throws IOException {
        ArrayList<ColumnMetadata> metadataList = new ArrayList<ColumnMetadata>();

        Get metadataGet = new Get(RowKeyFactory.buildColumnInfoKey(tableId, columnId));
        Result result = table.get(metadataGet);

        Map<byte[], byte[]> metadata = result.getFamilyMap(NIC);
        for (byte[] qualifier : metadata.keySet()) {
            // Only the qualifier matters for column metadata - value is not important
            String metadataString = new String(qualifier).toUpperCase();
            ColumnMetadata metaDataItem;

            try {
                metaDataItem = ColumnMetadata.valueOf(metadataString);
                metadataList.add(metaDataItem);
            } catch (IllegalArgumentException e) {

            }
        }

        return metadataList;
    }

    public Map<String, byte[]> parseRow(Result result, String tableName) throws IOException {
        TableInfo info = getTableInfo(tableName);

        //Get columns returned from Result
        Map<String, byte[]> columns = new HashMap<String, byte[]>();
        Map<byte[], byte[]> returnedColumns = result.getNoVersionMap().get(NIC);

        if (returnedColumns.size() == 1 && returnedColumns.containsKey(new byte[0])) {
            // The row of all nulls special case strikes again
            return columns;
        }

        //Loop through columns, add to returned map
        for (byte[] qualifier : returnedColumns.keySet()) {
            long columnId = ByteBuffer.wrap(qualifier).getLong();
            String columnName = info.getColumnNameById(columnId);
            columns.put(columnName, returnedColumns.get(qualifier));
        }

        return columns;
    }

    public boolean deleteRow(String tableName, UUID uuid) throws IOException {
        if (uuid == null) {
            return false;
        }

        TableInfo info = getTableInfo(tableName);
        long tableId = info.getId();

        List<Delete> deleteList = new LinkedList<Delete>();

        Filter uuidFilter = new UUIDFilter(uuid);

        //Delete dataRows
        byte[] dataRowKey = RowKeyFactory.buildDataKey(tableId, uuid);
        deleteList.add(new Delete(dataRowKey));

        //Scan the index rows
        byte[] indexStartKey = RowKeyFactory.buildValueIndexKey(tableId, 0L, new byte[0], uuid);
        byte[] indexEndKey = RowKeyFactory.buildValueIndexKey(tableId + 1, 0L, new byte[0], uuid);
        deleteList.addAll(scanAndDeleteAllUUIDs(indexStartKey, indexEndKey, uuid));

        //Scan the null index rows
        byte[] nullStartKey = RowKeyFactory.buildNullIndexKey(tableId, 0L, uuid);
        byte[] nullEndKey = RowKeyFactory.buildNullIndexKey(tableId + 1, 0L, uuid);
        deleteList.addAll(scanAndDeleteAllUUIDs(nullStartKey, nullEndKey, uuid));

        table.delete(deleteList);

        return true;
    }

    private List<Delete> scanAndDeleteAllUUIDs(byte[] startKey, byte[] endKey, UUID uuid) throws IOException {
        List<Delete> deleteList = new LinkedList<Delete>();

        Scan scan = ScanFactory.buildScan(startKey, endKey);

        Filter uuidFilter = new UUIDFilter(uuid);
        scan.setFilter(uuidFilter);

        for (Result result : table.getScanner(scan)) {
            deleteList.add(new Delete(result.getRow()));
        }

        return deleteList;
    }

    public boolean dropTable(String tableName) throws IOException {
        logger.info("Preparing to drop table " + tableName);
        TableInfo info = getTableInfo(tableName);
        long tableId = info.getId();

        deleteIndexRows(tableId);
        deleteDataRows(tableId);
        deleteColumnInfoRows(info);
        deleteColumns(tableId);
        deleteTableFromRoot(tableName);

        logger.info("Table " + tableName + " is no more!");

        return true;
    }

    public int deleteAllRows(String tableName) throws IOException {
        long tableId = getTableInfo(tableName).getId();

        logger.info("Deleting all rows from table " + tableName + " with tableId " + tableId);

        deleteIndexRows(tableId);
        int rowsAffected = deleteDataRows(tableId);

        return rowsAffected;
    }

    public int deleteDataRows(long tableId) throws IOException {
        logger.info("Deleting all data rows");
        byte[] prefix = ByteBuffer.allocate(9).put(RowType.DATA.getValue()).putLong(tableId).array();
        return deleteRowsWithPrefix(prefix);
    }

    public int deleteColumns(long tableId) throws IOException {
        // TODO: Update this to delete column info rows when they are done
        logger.info("Deleting all columns");
        byte[] prefix = ByteBuffer.allocate(9).put(RowType.COLUMNS.getValue()).putLong(tableId).array();
        return deleteRowsWithPrefix(prefix);
    }

    public int deleteIndexRows(long tableId) throws IOException {
        logger.info("Deleting all index rows");

        int affectedRows = 0;

        byte[] valuePrefix = ByteBuffer.allocate(9).put(RowType.PRIMARY_INDEX.getValue()).putLong(tableId).array();
        byte[] secondaryPrefix = ByteBuffer.allocate(9).put(RowType.SECONDARY_INDEX.getValue()).putLong(tableId).array();
        byte[] reversePrefix = ByteBuffer.allocate(9).put(RowType.REVERSE_INDEX.getValue()).putLong(tableId).array();
        byte[] nullPrefix = ByteBuffer.allocate(9).put(RowType.NULL_INDEX.getValue()).putLong(tableId).array();

        affectedRows += deleteRowsWithPrefix(valuePrefix);
        affectedRows += deleteRowsWithPrefix(secondaryPrefix);
        affectedRows += deleteRowsWithPrefix(reversePrefix);
        affectedRows += deleteRowsWithPrefix(nullPrefix);

        return affectedRows;
    }

    public int deleteColumnInfoRows(TableInfo info) throws IOException {
        logger.info("Deleting all column metadata rows");

        long tableId = info.getId();
        int affectedRows = 0;

        for (Long columnId : info.getColumnIds()) {
            byte[] metadataKey = RowKeyFactory.buildColumnInfoKey(tableId, columnId);
            affectedRows += deleteRowsWithPrefix(metadataKey);
        }

        return affectedRows;
    }

    public int deleteRowsWithPrefix(byte[] prefix) throws IOException {
        Scan scan = ScanFactory.buildScan();
        PrefixFilter filter = new PrefixFilter(prefix);
        scan.setFilter(filter);

        ResultScanner scanner = table.getScanner(scan);
        List<Delete> deleteList = new LinkedList<Delete>();
        int count = 0;

        for (Result result : scanner) {
            //Delete the data row key
            byte[] rowKey = result.getRow();
            Delete rowDelete = new Delete(rowKey);
            deleteList.add(rowDelete);

            ++count;
        }

        table.delete(deleteList);

        return count;
    }

    public void deleteTableFromRoot(String tableName) throws IOException {
        Delete delete = new Delete((RowKeyFactory.ROOT));
        delete.deleteColumns(NIC, tableName.getBytes());

        table.delete(delete);
    }

    public void setCacheSize(int cacheSize) {
        logger.info("Setting table scan row cache to " + cacheSize);
        ScanFactory.setCacheAmount(cacheSize);
    }

    public void setAutoFlushTables(boolean shouldFlushChangesImmediately) {
        this.table.setAutoFlush(shouldFlushChangesImmediately);

        logger.info(shouldFlushChangesImmediately
                ? "Changes to tables will be written to HBase immediately"
                : "Changes to tables will be written to HBase when the write buffer has become full");
    }

    public void setWriteBufferSize(long numBytes) {
        try {
            this.table.setWriteBufferSize(numBytes);
            this.writeBufferSize = numBytes;
        } catch (IOException e) {
            logger.error("Encountered an error setting write buffer size", e);
        }

        logger.info("Size of HBase write buffer set to " + numBytes + " bytes (" + (numBytes / 1024 / 1024) + " megabytes)");
    }

    public void flushWrites() {
        try {
            table.flushCommits();
        } catch (IOException e) {
            logger.error("Encountered an exception while flushing commits : ", e);
        }
    }

    public ResultScanner getScanner(ScanStrategy strategy) throws IOException {
        TableInfo info = getTableInfo(strategy.getTableName());

        Scan scan = strategy.getScan(info);

        return table.getScanner(scan);
    }
}