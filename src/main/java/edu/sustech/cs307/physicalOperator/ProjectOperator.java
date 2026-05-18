package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.ProjectTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ProjectOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final GroupByElement groupBy;
    private final List<OrderByElement> orderByElements;
    private final ArrayList<ColumnMeta> outputSchema;
    private final List<TabCol> projectSchema;
    private final boolean materializedMode;
    private List<Row> rows = new ArrayList<>();
    private int cursor;
    private Tuple currentTuple;

    public ProjectOperator(PhysicalOperator child, List<SelectItem<?>> selectItems,
                           GroupByElement groupBy, List<OrderByElement> orderByElements) throws DBException {
        this.child = child;
        this.selectItems = selectItems;
        this.groupBy = groupBy;
        this.orderByElements = orderByElements;
        this.outputSchema = buildOutputSchema();
        this.projectSchema = buildProjectSchema();
        this.materializedMode = hasAggregate() || groupBy != null || hasOrderBy() || hasExpressionProjection();
    }

    @Override
    public boolean hasNext() throws DBException {
        return materializedMode ? cursor < rows.size() : child.hasNext();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        if (materializedMode) {
            materialize();
        }
    }

    @Override
    public void Next() throws DBException {
        if (materializedMode) {
            currentTuple = cursor < rows.size() ? rows.get(cursor++).tuple : null;
            return;
        }
        if (!child.hasNext()) {
            currentTuple = null;
            return;
        }
        child.Next();
        Tuple inputTuple = child.Current();
        currentTuple = inputTuple == null ? null : new ProjectTuple(inputTuple, projectSchema);
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        currentTuple = null;
        rows.clear();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }

    private ArrayList<ColumnMeta> buildOutputSchema() throws DBException {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int offset = 0;
        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof AllColumns) {
                schema.addAll(child.outputSchema());
                continue;
            }
            if (expr instanceof Column column && item.getAliasName() == null) {
                schema.add(findColumn(column));
                continue;
            }
            ValueType type = outputType(expr);
            int len = type == ValueType.INTEGER ? Value.INT_SIZE : type == ValueType.FLOAT ? Value.FLOAT_SIZE : Value.CHAR_SIZE;
            schema.add(new ColumnMeta("", outputName(item), type, len, offset));
            offset += len;
        }
        return schema;
    }

    private List<TabCol> buildProjectSchema() throws DBException {
        List<TabCol> schema = new ArrayList<>();
        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof AllColumns) {
                for (ColumnMeta col : child.outputSchema()) {
                    schema.add(new TabCol(col.tableName, col.name));
                }
            } else if (expr instanceof Column col) {
                ColumnMeta meta = findColumn(col);
                schema.add(new TabCol(meta.tableName, meta.name));
            }
        }
        return schema;
    }

    private boolean hasExpressionProjection() {
        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (!(expr instanceof AllColumns) && !(expr instanceof Column)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOrderBy() {
        return orderByElements != null && !orderByElements.isEmpty();
    }

    private boolean hasAggregate() {
        for (SelectItem<?> item : selectItems) {
            if (item.getExpression() instanceof Function function && isAggregate(function)) {
                return true;
            }
        }
        return false;
    }

    private void materialize() throws DBException {
        List<Tuple> input = new ArrayList<>();
        while (child.hasNext()) {
            child.Next();
            if (child.Current() != null) {
                input.add(child.Current());
            }
        }

        rows = new ArrayList<>();
        if (hasAggregate() || groupBy != null) {
            materializeGrouped(input);
        } else {
            for (Tuple tuple : input) {
                rows.add(new Row(new TempTuple(projectValues(tuple)), orderKeys(tuple)));
            }
        }
        if (hasOrderBy()) {
            rows.sort(rowComparator());
        }
    }

    private void materializeGrouped(List<Tuple> input) throws DBException {
        List<List<Tuple>> groups = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Tuple tuple : input) {
            String key = groupKey(tuple);
            int index = keys.indexOf(key);
            if (index < 0) {
                keys.add(key);
                groups.add(new ArrayList<>());
                index = groups.size() - 1;
            }
            groups.get(index).add(tuple);
        }
        if (groups.isEmpty() && hasAggregate()) {
            groups.add(new ArrayList<>());
        }
        for (List<Tuple> group : groups) {
            Tuple sample = group.isEmpty() ? null : group.get(0);
            rows.add(new Row(new TempTuple(groupValues(group)), sample == null ? List.of() : orderKeys(sample)));
        }
    }

    private String groupKey(Tuple tuple) throws DBException {
        if (groupBy == null) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        ExpressionList<?> expressions = groupBy.getGroupByExpressionList();
        for (Expression expr : expressions.getExpressions()) {
            key.append(tuple.evaluateExpression(expr)).append('\u0001');
        }
        return key.toString();
    }

    private ArrayList<Value> projectValues(Tuple tuple) throws DBException {
        ArrayList<Value> values = new ArrayList<>();
        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof AllColumns) {
                values.addAll(List.of(tuple.getValues()));
            } else {
                values.add(evalScalar(tuple, expr));
            }
        }
        return values;
    }

    private ArrayList<Value> groupValues(List<Tuple> group) throws DBException {
        ArrayList<Value> values = new ArrayList<>();
        Tuple sample = group.isEmpty() ? null : group.get(0);
        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof Function function && isAggregate(function)) {
                values.add(evalAggregate(group, function));
            } else if (expr instanceof AllColumns && sample != null) {
                values.addAll(List.of(sample.getValues()));
            } else if (sample != null) {
                values.add(evalScalar(sample, expr));
            }
        }
        return values;
    }

    private Value evalScalar(Tuple tuple, Expression expr) throws DBException {
        if (expr instanceof StringValue stringValue) {
            return new Value(stringValue.getValue(), ValueType.CHAR);
        }
        return tuple.evaluateExpression(expr);
    }

    private Value evalAggregate(List<Tuple> group, Function function) throws DBException {
        String name = function.getName().toUpperCase();
        if (name.equals("COUNT")) {
            return new Value((long) group.size(), ValueType.INTEGER);
        }
        Expression arg = functionArg(function);
        Value best = null;
        for (Tuple tuple : group) {
            Value value = tuple.evaluateExpression(arg);
            if (value == null) {
                continue;
            }
            if (best == null
                    || (name.equals("MAX") && ValueComparer.compare(value, best) > 0)
                    || (name.equals("MIN") && ValueComparer.compare(value, best) < 0)) {
                best = value;
            }
        }
        return best == null ? new Value("", ValueType.CHAR) : best;
    }

    private List<Value> orderKeys(Tuple tuple) throws DBException {
        if (!hasOrderBy()) {
            return List.of();
        }
        ArrayList<Value> values = new ArrayList<>();
        for (OrderByElement orderBy : orderByElements) {
            values.add(tuple.evaluateExpression(orderBy.getExpression()));
        }
        return values;
    }

    private Comparator<Row> rowComparator() {
        return (left, right) -> {
            try {
                for (int i = 0; i < orderByElements.size(); i++) {
                    int cmp = ValueComparer.compare(left.orderKeys.get(i), right.orderKeys.get(i));
                    if (cmp != 0) {
                        return orderByElements.get(i).isAsc() ? cmp : -cmp;
                    }
                }
                return 0;
            } catch (DBException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private boolean isAggregate(Function function) {
        String name = function.getName().toUpperCase();
        return name.equals("COUNT") || name.equals("MAX") || name.equals("MIN");
    }

    private Expression functionArg(Function function) throws DBException {
        if (function.getParameters() == null || function.getParameters().isEmpty()) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }
        return function.getParameters().getExpressions().get(0);
    }

    private ValueType outputType(Expression expr) throws DBException {
        if (expr instanceof Column column) {
            return findColumn(column).type;
        }
        if (expr instanceof Function function) {
            String name = function.getName().toUpperCase();
            if (name.equals("COUNT")) {
                return ValueType.INTEGER;
            }
            return outputType(functionArg(function));
        }
        return ValueType.CHAR;
    }

    private ColumnMeta findColumn(Column column) throws DBException {
        String table = column.getTableName();
        ColumnMeta found = null;
        for (ColumnMeta meta : child.outputSchema()) {
            boolean tableMatches = table == null || table.isBlank() || meta.tableName.equalsIgnoreCase(table);
            if (tableMatches && meta.name.equalsIgnoreCase(column.getColumnName())) {
                if (found != null) {
                    throw new DBException(ExceptionTypes.InvalidSQL(column.toString(), "ambiguous column"));
                }
                found = meta;
            }
        }
        if (found == null) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(column.getColumnName()));
        }
        return found;
    }

    private String outputName(SelectItem<?> item) {
        if (item.getAliasName() != null) {
            return item.getAliasName();
        }
        return item.getExpression().toString();
    }

    private static class Row {
        private final Tuple tuple;
        private final List<Value> orderKeys;

        private Row(Tuple tuple, List<Value> orderKeys) {
            this.tuple = tuple;
            this.orderKeys = orderKeys;
        }
    }
}
