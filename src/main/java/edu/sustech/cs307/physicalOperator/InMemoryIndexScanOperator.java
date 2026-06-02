package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.InMemoryOrderedIndex;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class InMemoryIndexScanOperator implements PhysicalOperator {

    private final DBManager dbManager;
    private final String tableName;
    private final TableMeta tableMeta;
    private final InMemoryOrderedIndex index;
    private final Iterator<Map.Entry<Value, RID>> entryIterator;
    private final List<Map.Entry<Value, RID>> matchedEntries;
    private int cursor;
    private Tuple currentTuple;
    private RecordFileHandle fileHandle;

    public InMemoryIndexScanOperator(DBManager dbManager, String tableName,
                                     InMemoryOrderedIndex index,
                                     Iterator<Map.Entry<Value, RID>> entryIterator) {
        this.dbManager = dbManager;
        this.tableName = tableName;
        try {
            this.tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            throw new RuntimeException(e);
        }
        this.index = index;
        this.entryIterator = entryIterator;
        this.matchedEntries = new ArrayList<>();
        this.cursor = 0;
        this.currentTuple = null;
        this.fileHandle = null;
    }

    @Override
    public boolean hasNext() {
        return cursor < matchedEntries.size();
    }

    @Override
    public void Begin() throws DBException {
        // Drain the iterator into the list
        while (entryIterator.hasNext()) {
            matchedEntries.add(entryIterator.next());
        }
        cursor = 0;
        currentTuple = null;

        // Open file handle for reading records
        fileHandle = dbManager.getRecordManager().OpenFile(tableName);
    }

    @Override
    public void Next() {
        if (cursor >= matchedEntries.size()) {
            currentTuple = null;
            return;
        }
        Map.Entry<Value, RID> entry = matchedEntries.get(cursor++);
        RID rid = entry.getValue();
        try {
            Record record = fileHandle.GetRecord(rid);
            currentTuple = new TableTuple(tableName, tableMeta, record, rid);
        } catch (DBException e) {
            currentTuple = null;
        }
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        try {
            if (fileHandle != null) {
                dbManager.getRecordManager().CloseFile(fileHandle);
            }
        } catch (DBException e) {
            // ignore
        }
        matchedEntries.clear();
        currentTuple = null;
        fileHandle = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }
}