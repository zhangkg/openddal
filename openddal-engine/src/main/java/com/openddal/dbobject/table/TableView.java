/*
 * Copyright 2014-2016 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.dbobject.table;

import java.util.ArrayList;
import java.util.HashSet;

import com.openddal.command.Prepared;
import com.openddal.command.dml.Query;
import com.openddal.command.expression.Alias;
import com.openddal.command.expression.Comparison;
import com.openddal.command.expression.Expression;
import com.openddal.command.expression.ExpressionColumn;
import com.openddal.command.expression.ExpressionVisitor;
import com.openddal.command.expression.Parameter;
import com.openddal.dbobject.DbObject;
import com.openddal.dbobject.User;
import com.openddal.dbobject.index.Index;
import com.openddal.dbobject.index.IndexCondition;
import com.openddal.dbobject.schema.Schema;
import com.openddal.engine.Constants;
import com.openddal.engine.Session;
import com.openddal.message.DbException;
import com.openddal.result.LocalResult;
import com.openddal.util.IntArray;
import com.openddal.util.New;
import com.openddal.util.StringUtils;
import com.openddal.value.Value;

/**
 * A view is a virtual table that is defined by a query.
 *
 * @author Thomas Mueller
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
public class TableView extends Table {

    private static final long ROW_COUNT_APPROXIMATION = 100;

    private String querySQL;
    private ArrayList<Table> tables;
    private String[] columnNames;
    private Query viewQuery;
    private boolean recursive;
    private DbException createException;
    private User owner;
    private Query topQuery;
    private LocalResult recursiveResult;
    private boolean tableExpression;

    public TableView(Schema schema, int id, String name, String querySQL, ArrayList<Parameter> params,
            String[] columnNames, Session session, boolean recursive) {
        super(schema, name);
        init(querySQL, params, columnNames, session, recursive);
    }

    private static Query compileViewQuery(Session session, String sql) {
        Prepared p = session.prepare(sql);
        if (!(p instanceof Query)) {
            throw DbException.getSyntaxError(sql, 0);
        }
        return (Query) p;
    }

    /**
     * Create a temporary view out of the given query.
     *
     * @param session the session
     * @param owner the owner of the query
     * @param name the view name
     * @param query the query
     * @param topQuery the top level query
     * @return the view table
     */
    public static TableView createTempView(Session session, User owner, String name, Query query, Query topQuery) {
        Schema mainSchema = session.getDatabase().getSchema(Constants.SCHEMA_MAIN);
        String querySQL = query.getPlanSQL();
        TableView v = new TableView(mainSchema, 0, name, querySQL, query.getParameters(), null, session, false);
        if (v.createException != null) {
            throw v.createException;
        }
        v.setTopQuery(topQuery);
        v.setOwner(owner);
        v.setTemporary(true);
        return v;
    }

    private synchronized void init(String querySQL, ArrayList<Parameter> params, String[] columnNames, Session session,
            boolean recursive) {
        this.querySQL = querySQL;
        this.columnNames = columnNames;
        this.recursive = recursive;
        initColumnsAndTables(session);
    }

    private void initColumnsAndTables(Session session) {
        Column[] cols;
        // removeViewFromTables();
        try {
            Query query = compileViewQuery(session, querySQL);
            this.querySQL = query.getSQL();
            tables = New.arrayList(query.getTables());
            ArrayList<Expression> expressions = query.getExpressions();
            ArrayList<Column> list = New.arrayList();
            for (int i = 0, count = query.getColumnCount(); i < count; i++) {
                Expression expr = expressions.get(i);
                String name = null;
                if (columnNames != null && columnNames.length > i) {
                    name = columnNames[i];
                }
                if (name == null) {
                    name = expr.getAlias();
                }
                int type = expr.getType();
                long precision = expr.getPrecision();
                int scale = expr.getScale();
                int displaySize = expr.getDisplaySize();
                Column col = new Column(name, type, precision, scale, displaySize);
                col.setTable(this, i);
                // Fetch check constraint from view column source
                ExpressionColumn fromColumn = null;
                if (expr instanceof ExpressionColumn) {
                    fromColumn = (ExpressionColumn) expr;
                } else if (expr instanceof Alias) {
                    Expression aliasExpr = expr.getNonAliasExpression();
                    if (aliasExpr instanceof ExpressionColumn) {
                        fromColumn = (ExpressionColumn) aliasExpr;
                    }
                }
                if (fromColumn != null) {
                    Expression checkExpression = fromColumn.getColumn().getCheckConstraint(session, name);
                    if (checkExpression != null) {
                        col.addCheckConstraint(session, checkExpression);
                    }
                }
                list.add(col);
            }
            cols = new Column[list.size()];
            list.toArray(cols);
            createException = null;
            viewQuery = query;
        } catch (DbException e) {
            e.addSQL(querySQL);
            createException = e;
            // if it can't be compiled, then it's a 'zero column table'
            // this avoids problems when creating the view when opening the
            // database
            tables = New.arrayList();
            cols = new Column[0];
            if (recursive && columnNames != null) {
                cols = new Column[columnNames.length];
                for (int i = 0; i < columnNames.length; i++) {
                    cols[i] = new Column(columnNames[i], Value.STRING);
                }
                // index.setRecursive(true);
                createException = null;
            }
        }
        setColumns(cols);
        if (getId() != 0) {
            // addViewToTables();
        }
    }

    /**
     * Check if this view is currently invalid.
     *
     * @return true if it is
     */
    public boolean isInvalid() {
        return createException != null;
    }
    
    @Override
    public PlanItem getBestPlanItem(Session session, int[] masks,
            TableFilter filter) {
        PlanItem item = new PlanItem(); 
        if (recursive) {
            item.cost = 1000;
            return item;
        }        
        Query q = (Query) session.prepare(querySQL, true);
        if (masks != null) {
            IntArray paramIndex = new IntArray();
            for (int i = 0; i < masks.length; i++) {
                int mask = masks[i];
                if (mask == 0) {
                    continue;
                }
                paramIndex.add(i);
            }
            int len = paramIndex.size();
            for (int i = 0; i < len; i++) {
                int idx = paramIndex.get(i);
                int mask = masks[idx];
                int nextParamIndex = q.getParameters().size() + getParameterOffset();
                if ((mask & IndexCondition.EQUALITY) != 0) {
                    Parameter param = new Parameter(nextParamIndex);
                    q.addGlobalCondition(param, idx, Comparison.EQUAL_NULL_SAFE);
                } else {
                    if ((mask & IndexCondition.START) != 0) {
                        Parameter param = new Parameter(nextParamIndex);
                        q.addGlobalCondition(param, idx, Comparison.BIGGER_EQUAL);
                    }
                    if ((mask & IndexCondition.END) != 0) {
                        Parameter param = new Parameter(nextParamIndex);
                        q.addGlobalCondition(param, idx, Comparison.SMALLER_EQUAL);
                    }
                }
            }
            String sql = q.getPlanSQL();
            q = (Query) session.prepare(sql, true);
        }
        item.cost = q.getCost();
        return item;
    }

    @Override
    public boolean isQueryComparable() {
        if (!super.isQueryComparable()) {
            return false;
        }
        for (Table t : tables) {
            if (!t.isQueryComparable()) {
                return false;
            }
        }
        return !(topQuery != null && !topQuery.isEverything(ExpressionVisitor.QUERY_COMPARABLE_VISITOR));
    }

    @Override
    public void checkRename() {
        // ok
    }

    @Override
    public String getTableType() {
        return Table.VIEW;
    }

    @Override
    public String getSQL() {
        if (isTemporary()) {
            return "(\n" + StringUtils.indent(querySQL) + ")";
        }
        return super.getSQL();
    }

    public String getQuery() {
        return querySQL;
    }

    @Override
    public ArrayList<Index> getIndexes() {
        return null;
    }

    @Override
    public Index getUniqueIndex() {
        return null;
    }

    public User getOwner() {
        return owner;
    }

    private void setOwner(User owner) {
        this.owner = owner;
    }

    private void setTopQuery(Query topQuery) {
        this.topQuery = topQuery;
    }

    @Override
    public long getRowCountApproximation() {
        return ROW_COUNT_APPROXIMATION;
    }

    public int getParameterOffset() {
        return topQuery == null ? 0 : topQuery.getParameters().size();
    }

    @Override
    public boolean isDeterministic() {
        if (recursive || viewQuery == null) {
            return false;
        }
        return viewQuery.isEverything(ExpressionVisitor.DETERMINISTIC_VISITOR);
    }

    public LocalResult getRecursiveResult() {
        return recursiveResult;
    }

    public void setRecursiveResult(LocalResult value) {
        if (recursiveResult != null) {
            recursiveResult.close();
        }
        this.recursiveResult = value;
    }

    public boolean isTableExpression() {
        return tableExpression;
    }

    public void setTableExpression(boolean tableExpression) {
        this.tableExpression = tableExpression;
    }

    @Override
    public void addDependencies(HashSet<DbObject> dependencies) {
        super.addDependencies(dependencies);
        if (tables != null) {
            for (Table t : tables) {
                if (!Table.VIEW.equals(t.getTableType())) {
                    t.addDependencies(dependencies);
                }
            }
        }
    }
}
