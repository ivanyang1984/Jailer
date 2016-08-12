/*
 * Copyright 2007 - 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.jailer.database;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.sf.jailer.CommandLineParser;
import net.sf.jailer.Configuration;
import net.sf.jailer.TransformerFactory;
import net.sf.jailer.database.Session.AbstractResultSetReader;
import net.sf.jailer.database.Session.ResultSetReader;
import net.sf.jailer.datamodel.Column;
import net.sf.jailer.datamodel.Table;
import net.sf.jailer.util.CellContentConverter;
import net.sf.jailer.util.Quoting;
import net.sf.jailer.util.SqlUtil;

/**
 * A {@link ResultSetReader} that writes the read rows as SQL-DELETE-statements 
 * into the deletion-script.
 * 
 * @author Ralf Wisser
 */
public class DeletionTransformer extends AbstractResultSetReader {

    /**
     * The table to read from.
     */
    private final Table table;
    
    /**
     * The file to write to.
     */
    private final OutputStreamWriter scriptFileWriter;
    
    /**
     * For building compact delete-statements.
     */
    private StatementBuilder deleteStatementBuilder;

    /**
     * Current session;
     */
    private final Session session;
    
    /**
     * For quoting of column names.
     */
    private final Quoting quoting;

    /**
     * Factory.
     */
    public static class Factory implements TransformerFactory {
    	
	    private final int maxBodySize;
		private final OutputStreamWriter scriptFileWriter;
		private final Session session;

		/**
	     * Constructor.
	     * 
	     * @param table the table to read from
	     * @param scriptFileWriter the file to write to
	     * @param maxBodySize maximum length of SQL values list (for generated deletes)
	     */
		public Factory(OutputStreamWriter scriptFileWriter, int maxBodySize, Session session) {
	        this.maxBodySize = maxBodySize;
	        this.scriptFileWriter = scriptFileWriter;
	        this.session = session;
    	}

		/**
		 * Creates transformer (as {@link ResultSetReader} which 
		 * transforms rows of a given table into an external representation.
		 * 
		 * @param table the table
		 * @return a transformer
		 */
		@Override
		public ResultSetReader create(Table table) throws SQLException {
			return new DeletionTransformer(table, scriptFileWriter, maxBodySize, session);
		}
    };

    /**
     * Constructor.
     * 
     * @param table the table to read from
     * @param scriptFileWriter the file to write to
     * @param maxBodySize maximum length of SQL values list (for generated deletes)
     */
    private DeletionTransformer(Table table, OutputStreamWriter scriptFileWriter, int maxBodySize, Session session) throws SQLException {
        this.table = table;
        this.scriptFileWriter = scriptFileWriter;
        deleteStatementBuilder = new StatementBuilder(maxBodySize);
        this.quoting = new Quoting(session);
        this.session = session;
        
        if (table.primaryKey.getColumns().isEmpty()) {
        	throw new RuntimeException("Unable to delete from table \"" + table.getName() + "\".\n" +
        			"No primary key.");
        }
    }
    
    /**
     * Reads result-set and writes into export-script.
     * 
     * @param resultSet the result set
     */
    public void readCurrentRow(ResultSet resultSet) throws SQLException {
        try {
        	final SQLDialect currentDialect = Configuration.forDbms(session).getSqlDialect();
            
        	CellContentConverter cellContentConverter = getCellContentConverter(resultSet, session);
			if (SqlUtil.dbms == DBMS.SYBASE || (currentDialect != null && !currentDialect.supportsInClauseForDeletes)) {
        		String deleteHead = "Delete from " + qualifiedTableName(table) + " Where (";
                boolean firstTime = true;
                String item = "";
                for (Column pkColumn: table.primaryKey.getColumns()) {
                	item += (firstTime? "" : " and ") + quoting.quote(pkColumn.name) + "="
                    		+ cellContentConverter.toSql(cellContentConverter.getObject(resultSet, quoting.unquote(pkColumn.name)));
                    firstTime = false;
                }
                if (!deleteStatementBuilder.isAppendable(deleteHead, item)) {
	                writeToScriptFile(deleteStatementBuilder.build());
	            }
	            deleteStatementBuilder.append(deleteHead, item, ") or (", ");\n");
              	} else {
	            String deleteHead;
	            String item;
	            if (table.primaryKey.getColumns().size() == 1) {
	                deleteHead = "Delete from " + qualifiedTableName(table) + " Where " + quoting.quote(table.primaryKey.getColumns().get(0).name) + " in (";
	                item = cellContentConverter.toSql(cellContentConverter.getObject(resultSet, quoting.unquote(table.primaryKey.getColumns().get(0).name)));
	            } else {
	                deleteHead = "Delete from " + qualifiedTableName(table) + " Where (";
	                item = "(";
	                boolean firstTime = true;
	                for (Column pkColumn: table.primaryKey.getColumns()) {
	                    deleteHead += (firstTime? "" : ", ") + quoting.quote(pkColumn.name);
	                    item += (firstTime? "" : ", ") + cellContentConverter.toSql(cellContentConverter.getObject(resultSet, quoting.unquote(pkColumn.name)));
	                    firstTime = false;
	                }
	                item += ")";
	                deleteHead += ") in (";
	                if (currentDialect.needsValuesKeywordForDeletes) {
	                	deleteHead += "values ";
	                }
	            }
	            if (!deleteStatementBuilder.isAppendable(deleteHead, item)) {
	                writeToScriptFile(deleteStatementBuilder.build());
	            }
	            deleteStatementBuilder.append(deleteHead, item, ", ", ");\n");
        	}
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets qualified table name.
     * 
     * @param t the table
     * @return qualified name of t
     */
    private String qualifiedTableName(Table t) {
    	String schema = t.getOriginalSchema("");
    	String mappedSchema = CommandLineParser.getInstance().getSourceSchemaMapping().get(schema);
    	if (mappedSchema != null) {
    		schema = mappedSchema;
    	}
    	if (schema.length() == 0) {
    		return quoting.quote(t.getUnqualifiedName());
    	}
		return quoting.quote(schema) + "." + quoting.quote(t.getUnqualifiedName());
	}

    /**
     * Writes into script.
     */
    private void writeToScriptFile(String content) throws IOException {
        synchronized (scriptFileWriter) {
        	if (SqlUtil.dbms == DBMS.ORACLE) {
       			scriptFileWriter.write(SqlUtil.splitDMLStatement(content, 2400));
        	} else {
        		scriptFileWriter.write(content);
        	}
        }
    }

    /**
     * Finalizes reading.
     */
    public void close() {
        try {
            writeToScriptFile(deleteStatementBuilder.build());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
}
