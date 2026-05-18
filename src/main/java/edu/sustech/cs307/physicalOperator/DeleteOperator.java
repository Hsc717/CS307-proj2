package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;

public class DeleteOperator implements PhysicalOperator {
    private final SeqScanOperator scanner;
    private final Expression whereExpr;
    private int deletedRows;
    private boolean done;

    public DeleteOperator(PhysicalOperator inputOperator, String tableName, Expression whereExpr) {
        if (!(inputOperator instanceof SeqScanOperator seqScanOperator)) {
            throw new RuntimeException("DeleteOperator only accepts SeqScanOperator input");
        }
        this.scanner = seqScanOperator;
        this.whereExpr = whereExpr;
    }

    @Override
    public boolean hasNext() {
        return !done;
    }

    @Override
    public void Begin() throws DBException {
        scanner.Begin();
        RecordFileHandle fileHandle = scanner.getFileHandle();
        while (scanner.hasNext()) {
            scanner.Next();
            TableTuple tuple = (TableTuple) scanner.Current();
            if (tuple != null && (whereExpr == null || tuple.eval_expr(whereExpr))) {
                fileHandle.DeleteRecord(tuple.getRID());
                deletedRows++;
            }
        }
    }

    @Override
    public void Next() {
        done = true;
    }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(deletedRows, ValueType.INTEGER));
        return new TempTuple(values);
    }

    @Override
    public void Close() {
        scanner.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("delete", "numberOfDeletedRows", ValueType.INTEGER, 0, 0));
        return schema;
    }
}
