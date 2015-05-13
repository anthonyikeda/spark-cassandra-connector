package org.apache.spark.sql.cassandra

import org.apache.spark.sql.catalyst.analysis.EliminateSubQueries
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.sources.{ResolvedDataSource, InsertableRelation, LogicalRelation}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{AnalysisException, DataFrame, SQLContext, SaveMode}
import CassandraDefaultSource._
import Commands._

/** Create a datasource table metadata in metastore */
private[cassandra] case class CreateMetastoreDataSource(
    tableName: String,
    userSpecifiedSchema: Option[StructType],
    provider: String,
    options: Map[String, String],
    allowExisting: Boolean) extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef = cc.catalog.tableRefFrom(Seq(cc.getKeyspace, tableName))
    if (cc.tableExistsInMetastore(tableRef)) {
      if (allowExisting) {
        return Seq.empty[Row]
      } else {
        throw new AnalysisException(s"Table $tableName already exists.")
      }
    }
    cc.registerTable(
      tableRef,
      provider,
      userSpecifiedSchema,
      options)
    Seq.empty[Row]
  }
}

/** Create a datasource table metadata in metastore for a AS Select query */
private[cassandra] case class CreateMetastoreDataSourceAsSelect(
    tableName: String,
    provider: String,
    mode: SaveMode,
    options: Map[String, String],
    query: LogicalPlan) extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef = cc.catalog.tableRefFrom(Seq(cc.getKeyspace, tableName))
    var existingSchema = None: Option[StructType]
    var createMetastoreTable = false
    if (cc.tableExistsInMetastore(tableRef)) {
      // Check if we need to throw an exception or just return.
      mode match {
        case SaveMode.ErrorIfExists =>
          throw new AnalysisException(s"Table $tableName already exists. " +
            s"If you are using saveAsTable, you can set SaveMode to SaveMode.Append to " +
            s"insert data into the table or set SaveMode to SaveMode.Overwrite to overwrite" +
            s"the existing data. " +
            s"Or, if you are using SQL CREATE TABLE, you need to drop $tableName first.")
        case SaveMode.Ignore =>
          // Since the table already exists and the save mode is Ignore, we will just return.
          return Seq.empty[Row]
        case SaveMode.Append =>
          // Check if the specified data source match the data source of the existing table.
          val resolved =
            ResolvedDataSource(sqlContext, Some(query.schema), provider, options)
          val createdRelation = LogicalRelation(resolved.relation)
          EliminateSubQueries(sqlContext.table(tableName).logicalPlan) match {
            case l @ LogicalRelation(i: InsertableRelation) =>
              if (i != createdRelation.relation) {
                val errorDescription =
                  s"Cannot append to table $tableName because the resolved relation does not " +
                    s"match the existing relation of $tableName. " +
                    s"You can use insertInto($tableName, false) to append this DataFrame to the " +
                    s"table $tableName and using its data source and options."
                val errorMessage =
                  s"""
                |$errorDescription
                |== Relations ==
                |${sideBySide(
                    s"== Expected Relation ==" ::
                      l.toString :: Nil,
                    s"== Actual Relation ==" ::
                      createdRelation.toString :: Nil).mkString("\n")}
              """.stripMargin
                throw new AnalysisException(errorMessage)
              }
              existingSchema = Some(l.schema)
            case o =>
              throw new AnalysisException(s"Saving data in ${o.toString} is not supported.")
          }
        case SaveMode.Overwrite =>
          cc.sql(s"DROP TABLE $tableName")
          // Need to create the table again.
          createMetastoreTable = true
      }
    } else {
      // The table does not exist. We need to create it in metastore.
      createMetastoreTable = true
    }

    val data = DataFrame(cc, query)
    val df = existingSchema match {
      // If we are inserting into an existing table, just use the existing schema.
      case Some(schema) => sqlContext.createDataFrame(data.queryExecution.toRdd, schema)
      case None => data
    }

    val optionsMayWithTableIdent =
      if(cassandraDatasource(provider))
        cc.optionsWithTableRef(tableRef, options)
      else
        options
    // Create the relation based on the data of df.
    val resolved = ResolvedDataSource(sqlContext, provider, mode, optionsMayWithTableIdent, df)
    if (createMetastoreTable) {
      // We will use the schema of resolved.relation as the schema of the table (instead of
      // the schema of df). It is important since the nullability may be changed by the relation
      // provider (for example, see org.apache.spark.sql.parquet.DefaultSource).
      cc.registerTable(
        tableRef,
        provider,
        Some(resolved.relation.schema),
        options)
    }

    Seq.empty[Row]
  }
}

/**
 * Drops a table from the metastore and removes it if it is cached.
 */
private[cassandra] case class DropTable(tableIdentifier: Seq[String]) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef : TableRef = cc.catalog.tableRefFrom(tableIdentifier)
    try {
      cc.cacheManager.tryUncacheQuery(cc.table(tableRef.table))
    } catch {
      // This table's metadata is not in
      case _: org.apache.hadoop.hive.ql.metadata.InvalidTableException =>
      // Other Throwables can be caused by users providing wrong parameters in OPTIONS
      // (e.g. invalid paths). We catch it and log a warning message.
      // Users should be able to drop such kinds of tables regardless if there is an error.
      case e: Throwable => log.warn(s"${e.getMessage}")
    }
    cc.unregisterTable(tableRef)
    Seq.empty[Row]
  }
}

/**
 * Rename a table from the metastore.
 */
private[cassandra] case class RenameTable(tableIdentifier: Seq[String], newName: String) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef : TableRef = cc.catalog.tableRefFrom(tableIdentifier)
    try {
      //TODO OSS SPARK should update it to use tableIdentifier
      cc.cacheManager.tryUncacheQuery(cc.table(tableRef.table))
    } catch {
      // This table's metadata is not in
      case _: org.apache.hadoop.hive.ql.metadata.InvalidTableException =>
      // Other Throwables can be caused by users providing wrong parameters in OPTIONS
      // (e.g. invalid paths). We catch it and log a warning message.
      // Users should be able to drop such kinds of tables regardless if there is an error.
      case e: Throwable => log.warn(s"${e.getMessage}")
    }
    val metadata = cc.getTableMetadata(tableRef)
    if (metadata.nonEmpty) {
      cc.unregisterTable(tableRef)
      val newTableIdent = TableRef(newName, tableRef.keyspace, tableRef.cluster)
      val data = metadata.get
      cc.registerTable(newTableIdent, data.source, data.schema, data.options)
    }
    Seq.empty[Row]
  }
}

/** Set table schema */
private[cassandra] case class SetTableSchema(tableIdentifier: Seq[String], schemaJsonString: String) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef : TableRef = cc.catalog.tableRefFrom(tableIdentifier)
    cc.setTableSchema(tableRef, schemaJsonString)
    Seq.empty[Row]
  }
}

/** Set an option of table options */
private[cassandra] case class SetTableOption(
    tableIdentifier: Seq[String],
    key: String,
    value: String) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef : TableRef = cc.catalog.tableRefFrom(tableIdentifier)
    cc.setTableOption(tableRef, key, value)
    Seq.empty[Row]
  }
}

/** Remove an option of table options */
private[cassandra] case class RemoveTableOption(tableIdentifier: Seq[String], key: String) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef : TableRef = cc.catalog.tableRefFrom(tableIdentifier)
    cc.removeTableOption(tableRef, key)
    Seq.empty[Row]
  }
}

/** Remove table schema */
private[cassandra] case class RemoveTableSchema(tableIdentifier: Seq[String]) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tableRef : TableRef = cc.catalog.tableRefFrom(tableIdentifier)
    cc.removeTableSchema(tableRef)
    Seq.empty[Row]
  }
}

/** Change the current used cluster */
private[cassandra] case class UseCluster(cluster: String) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    sqlContext.useCluster(cluster)
    Seq.empty[Row]
  }
}

/** Change the current used database */
private[cassandra] case class UseDatabase(databaseIdentifier: Seq[String]) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val (clusterName, databaseName) = clusterDatabaseNamesFrom(databaseIdentifier, sqlContext)
    sqlContext.useCluster(clusterName)
    sqlContext.useDatabase(databaseName)
    Seq.empty[Row]
  }
}

/** Show table names for a database of a cluster */
private[cassandra] case class ShowTables(databaseIdentifier: Seq[String]) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val (clusterName, databaseName) = clusterDatabaseNamesFrom(databaseIdentifier, sqlContext)
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val tables = cc.getTables(Option(databaseName), Option(clusterName))
    tables.map(_._1).map(name => Row.fromSeq(Seq(name)))
  }
}

/** Show database names for a cluster */
private[cassandra] case class ShowDatabases(clusterIdentifier: Seq[String]) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val id = clusterIdentifier.reverse.lift
    val clusterName = id(0).getOrElse(sqlContext.getCluster)
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val databases = cc.getDatabases(Option(clusterName))
    databases.map(name => Row.fromSeq(Seq(name)))
  }
}

/** Show cluster names */
private[cassandra] case class ShowClusters() extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    val clusters = cc.getClusters()
    clusters.map(name => Row.fromSeq(Seq(name)))
  }
}

/** Create a database in metastore */
private[cassandra] case class CreateDatabase(databaseIdentifier: Seq[String]) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val (clusterName, databaseName) = clusterDatabaseNamesFrom(databaseIdentifier, sqlContext)
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    cc.createDatabase(databaseName, Option(clusterName))
    Seq.empty[Row]
  }
}

/** Create a cluster in metastore */
private[cassandra] case class CreateCluster(cluster: String) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    cc.createCluster(cluster)
    Seq.empty[Row]
  }
}

/** Drop a database from metastore */
private[cassandra] case class DropDatabase(databaseIdentifier: Seq[String]) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val (clusterName, databaseName) = clusterDatabaseNamesFrom(databaseIdentifier, sqlContext)
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    cc.unregisterDatabase(databaseName, Option(clusterName))
    Seq.empty[Row]
  }
}

/** Drop a cluster from metastore */
private[cassandra] case class DropCluster(cluster: String) extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val cc = sqlContext.asInstanceOf[CassandraSQLContext]
    cc.unregisterCluster(cluster)
    Seq.empty[Row]
  }
}

object Commands {
  /** Get cluster name and database name from database identifier */
  def clusterDatabaseNamesFrom(databaseIdentifier: Seq[String], sqlContext: SQLContext) : (String, String) = {
    val id = databaseIdentifier.reverse.lift
    val clusterName = id(1).getOrElse(sqlContext.getCluster)
    val databaseName = id(0).getOrElse(sqlContext.getDatabase)
    (clusterName, databaseName)
  }
}