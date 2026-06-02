package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;

public abstract class Tuple {
    public abstract Value getValue(TabCol tabCol) throws DBException;

    public abstract TabCol[] getTupleSchema();

    public abstract Value[] getValues() throws DBException;

    public boolean eval_expr(Expression expr) throws DBException {
        return evaluateCondition(this, expr);
    }

    private boolean evaluateCondition(Tuple tuple, Expression whereExpr) throws DBException {
        if (whereExpr == null) {
            return true;
        }
        if (whereExpr instanceof AndExpression andExpr) {
            return evaluateCondition(tuple, andExpr.getLeftExpression())
                    && evaluateCondition(tuple, andExpr.getRightExpression());
        } else if (whereExpr instanceof OrExpression orExpr) {
            return evaluateCondition(tuple, orExpr.getLeftExpression())
                    || evaluateCondition(tuple, orExpr.getRightExpression());
        } else if (whereExpr instanceof InExpression inExpression) {
            return evaluateInExpression(tuple, inExpression);
        } else if (whereExpr instanceof ExistsExpression existsExpression) {
            return evaluateExistsExpression(tuple, existsExpression);
        } else if (whereExpr instanceof Parenthesis parenthesis) {
            return evaluateCondition(tuple, parenthesis.getExpression());
        } else if (whereExpr instanceof NotExpression notExpression) {
            return !evaluateCondition(tuple, notExpression.getExpression());
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        } else {
            return true;
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) throws DBException {
        Expression leftExpr = binaryExpr.getLeftExpression();
        Expression rightExpr = binaryExpr.getRightExpression();
        String operator = binaryExpr.getStringExpression();
        Value leftValue = valueOf(tuple, leftExpr);
        Value rightValue = valueOf(tuple, rightExpr);
        if (leftValue == null || rightValue == null) {
            return false;
        }

        int comparisonResult = ValueComparer.compare(leftValue, rightValue);
        return switch (operator) {
            case "=" -> comparisonResult == 0;
            case ">" -> comparisonResult > 0;
            case ">=" -> comparisonResult >= 0;
            case "<" -> comparisonResult < 0;
            case "<=" -> comparisonResult <= 0;
            case "<>", "!=" -> comparisonResult != 0;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateInExpression(Tuple tuple, InExpression inExpression) throws DBException {
        Value leftValue = valueOf(tuple, inExpression.getLeftExpression());
        if (leftValue == null) {
            return false;
        }
        Expression right = inExpression.getRightExpression();
        ExpressionList<Expression> values = null;
        if (right instanceof ParenthesedExpressionList<?> list) {
            values = (ExpressionList<Expression>) list;
        } else if (right instanceof ExpressionList<?> list) {
            values = (ExpressionList<Expression>) list;
        }
        if (values == null) {
            return false;
        }
        boolean matched = false;
        for (Expression expression : values.getExpressions()) {
            Value rightValue = valueOf(tuple, expression);
            if (rightValue != null && ValueComparer.compare(leftValue, rightValue) == 0) {
                matched = true;
                break;
            }
        }
        return inExpression.isNot() ? !matched : matched;
    }

    /**
     * Evaluate EXISTS / NOT EXISTS expression.
     * For a tuple, EXISTS checks if a subquery returns any rows.
     * In a tuple-level filter, we treat EXISTS(subquery) as true if the subquery
     * would return at least one row. Since we don't have a subquery executor
     * here, we handle it by evaluating the inner expression against the tuple.
     */
    private boolean evaluateExistsExpression(Tuple tuple, ExistsExpression existsExpression) throws DBException {
        Expression rightExpr = existsExpression.getRightExpression();
        if (rightExpr instanceof Parenthesis parenthesis) {
            // For EXISTS (subquery), evaluate the inner expression
            // If it contains references to the current tuple, evaluate it
            boolean result = evaluateCondition(tuple, parenthesis.getExpression());
            return existsExpression.isNot() ? !result : result;
        }
        return false;
    }

    private Value valueOf(Tuple tuple, Expression expression) throws DBException {
        if (expression instanceof Column column) {
            return tuple.getColumnValue(column);
        }
        return getConstantValue(expression);
    }

    private Value getConstantValue(Expression expr) {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        }
        return null; // Unsupported constant type
    }

    public Value evaluateExpression(Expression expr) throws DBException {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        } else if (expr instanceof Column) {
            Column col = (Column) expr;
            return getColumnValue(col);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

    public Value getColumnValue(Column column) throws DBException {
        String tableName = column.getTableName();
        String columnName = column.getColumnName();
        if (tableName != null && !tableName.isBlank()) {
            return getValue(new TabCol(tableName, columnName));
        }

        Value result = null;
        for (TabCol tabCol : getTupleSchema()) {
            if (tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                Value value = getValue(tabCol);
                if (value != null) {
                    if (result != null) {
                        throw new DBException(ExceptionTypes.InvalidSQL(column.toString(), "ambiguous column"));
                    }
                    result = value;
                }
            }
        }
        return result;
    }

}