package com.atlassian.db.replica.internal;

public class SQLOpeartionType {

    public static enum SQLType {
        WRITE, READ_ONLY, NOT_KNOWN
    }

    private final SqlFunction sqlFunction;

    public SQLOpeartionType(SqlFunction sqlFunction) {
        this.sqlFunction = sqlFunction;
    }

    /**
     * Arbitrially judge based on sql param whether sql is WRITE
     */
    public boolean whetherSQLisWrite(String sql){
        //method to arbitrially judge whether sql isWrite or
        //maybe inspired MaryPri
        //https://bitbucket.org/atlassian/marypri/src/00f9cad9d58131e6d9d27be8de0ee5d01b3cbfd3/src/main/kotlin/io/atlassian/micros/marypri/sql/ReadWriteClassifier.kt#lines-14
        return isWriteOpearation(sql) &&
                isSelectForUpdate(sql) &&
                !isSQLSet(sql);
    }


    public boolean isWriteOpearation(String sql) {
        boolean isWriteOperation = sqlFunction.isFunctionCall(sql) || isUpdate(sql) || isDelete(sql);

        return isWriteOperation;

    }

    public boolean isSelectForUpdate(String sql) {
        return sql != null && (sql.endsWith("for update") || sql.endsWith("FOR UPDATE"));
    }

    public boolean isSQLSet(String sql) {
        return sql.startsWith("set");
    }

    public boolean isUpdate(String sql) {
        return sql != null && (sql.startsWith("update") || sql.startsWith("UPDATE"));
    }

    public boolean isDelete(String sql) {
        return sql != null && (sql.startsWith("delete") || sql.startsWith("DELETE"));
    }
}
