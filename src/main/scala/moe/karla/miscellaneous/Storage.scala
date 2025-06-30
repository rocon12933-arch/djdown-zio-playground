package moe.karla.miscellaneous



import io.getquill.*
import io.getquill.jdbczio.Quill

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource



object Storage:
  
  
  def dataSource(poolSize: Int) = {
    val config = new HikariConfig()
    //config.setJdbcUrl("jdbc:h2:./data")
    config.setJdbcUrl("jdbc:sqlite:./data.db")
    //config.setUsername("sa")
    config.setTransactionIsolation("TRANSACTION_SERIALIZABLE")
    config.setMaximumPoolSize(poolSize)
    HikariDataSource(config)
  }
  

  val dataSourceLayer = Quill.DataSource.fromDataSource(dataSource(poolSize = 1))