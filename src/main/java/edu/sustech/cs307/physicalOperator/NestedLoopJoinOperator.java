package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class NestedLoopJoinOperator implements PhysicalOperator {
    private final PhysicalOperator leftOperator;
    private final PhysicalOperator rightOperator;
    private final ArrayList<ColumnMeta> outputSchema;
    private final TabCol[] tupleSchema;
    private final List<Tuple> rows = new ArrayList<>();
    private int cursor;
    private Tuple current;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
                                  Collection<Expression> expr) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.outputSchema = new ArrayList<>();
        this.outputSchema.addAll(leftOperator.outputSchema());
        this.outputSchema.addAll(rightOperator.outputSchema());
        this.tupleSchema = this.outputSchema.stream()
                .map(col -> new TabCol(col.tableName, col.name))
                .toArray(TabCol[]::new);
    }

    @Override
    public boolean hasNext() {
        return cursor < rows.size();
    }

    @Override
    public void Begin() throws DBException {
        List<Tuple> leftRows = drain(leftOperator);
        List<Tuple> rightRows = drain(rightOperator);
        for (Tuple left : leftRows) {
            for (Tuple right : rightRows) {
                rows.add(new JoinTuple(left, right, tupleSchema));
            }
        }
    }

    private List<Tuple> drain(PhysicalOperator operator) throws DBException {
        ArrayList<Tuple> result = new ArrayList<>();
        operator.Begin();
        while (operator.hasNext()) {
            operator.Next();
            if (operator.Current() != null) {
                result.add(operator.Current());
            }
        }
        return result;
    }

    @Override
    public void Next() {
        current = cursor < rows.size() ? rows.get(cursor++) : null;
    }

    @Override
    public Tuple Current() {
        return current;
    }

    @Override
    public void Close() {
        leftOperator.Close();
        rightOperator.Close();
        rows.clear();
        current = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }
}
