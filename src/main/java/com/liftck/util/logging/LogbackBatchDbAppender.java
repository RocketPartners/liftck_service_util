/**
 * 
 */
package com.liftck.util.logging;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.db.DBHelper;

/**
 * @author tc-rocket
 *
 * This is copied/inspired by com.liftrmt.util.JdbcAppender
 *
 */
public class LogbackBatchDbAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
{
   Logger                    log               = LoggerFactory.getLogger(getClass().getName());
   String                    thisLogger        = getClass().getName();

   static final int          MAX_MSG_LENGTH    = 255;
   static final int          MAX_MSGKEY_LENGTH = 100;
   static final int          MAX_STACK_LENGTH  = 10000;                                        // max number of characters of a stack trace

   // configureable properties
   DataSource                dataSource;
   String                    tableName;
   String                    service;
   int                       maxMessagesPerDay = 1000;
   int                       maxQueue          = 10000;

   // internal members
   private String            insertSql;
   private int               messageNum        = 0;
   private SimpleDateFormat  dayIdFormat       = new SimpleDateFormat("yyyyMMdd");
   private String            buildVersion;
   private String            machine;
   private String            machineIp;

   Connection                connection        = null;
   LinkedList<ILoggingEvent> queue             = null;

   Thread                    writerThread      = null;
   String                    writerThreadName  = "LogbackBatchDbAppender.writerThread";

   @Override
   public void start()
   {
      if (dataSource == null)
      {
         throw new RuntimeException("LogbackDbAppender not initialized - You must specify a datasource\n" + getExampleUsage());
      }
      if (tableName == null)
      {
         throw new RuntimeException("LogbackDbAppender not initialized - You must specify a tableName\n" + getExampleUsage());
      }
      if (service == null)
      {
         throw new RuntimeException("LogbackDbAppender not initialized - You must specify a service\n" + getExampleUsage());
      }

      loadBuildInfo();

      try
      {
         InetAddress localHost = InetAddress.getLocalHost();
         machine = localHost.getHostName();
         machineIp = localHost.getHostAddress();
      }
      catch (Exception e)
      {
         System.out.println("Warning machine name and ip could not be found in LogbackDbAppender");
         machine = "unknown";
         machineIp = "0.0.0.0";
      }

      buildInsertSql();
      this.init();
      super.start();
   }

   @Override
   public void stop()
   {

      try
      {
         if (writerThread.isAlive())
         {
            writerThread.interrupt();
         }
      }
      catch (Exception ex)
      {

      }

      if (connection != null)
      {
         try
         {
            connection.close();
         }
         catch (Exception ex)
         {

         }
      }
   }

   @Override
   protected void append(ILoggingEvent event)
   {

      //don't send log message created by the database logging thread
      //to the database, this will more then likely cause an infinate loop
      if (event.getThreadName().equals(writerThreadName))
         return;

      //don't send log messages generated by this appender to the DB
      if (thisLogger.equals(event.getLoggerName()))
         return;

      push(event);
   }

   protected synchronized void init()
   {
      queue = new LinkedList<ILoggingEvent>();

      writerThread = new Thread(new Runnable()
         {
            @Override
            public void run()
            {
               insertEvents();
            }
         }, writerThreadName);
      writerThread.setDaemon(true);
      writerThread.start();
   }

   protected void insertEvents()
   {
      PreparedStatement statement = null;

      while (true)
      {
         try
         {
            List<ILoggingEvent> events = pop();

            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(insertSql);

            while (events != null && events.size() > 0)
            {

               for (ILoggingEvent event : events)
               {
                  populateStatement(event, statement);
                  statement.addBatch();
               }

               statement.executeBatch();
               connection.commit();

               synchronized (this)
               {
                  //check to see if new events have arrived and process them 
                  //immediately if so
                  if (queue.size() > 0)
                  {
                     events = pop();
                  }
                  else
                  {
                     events = null;
                  }
               }
            }

            statement.close();
         }
         catch (Exception ex)
         {
            log.warn("Exception committing a batch of log events to the db.  These log events will be thrown away.", ex);
            DBHelper.closeStatement(statement);
            DBHelper.closeConnection(connection);
         }
      }
   }

   protected void populateStatement(ILoggingEvent evt, PreparedStatement statement) throws SQLException
   {

      Timestamp ts = new Timestamp(evt.getTimeStamp());
      Calendar cal = Calendar.getInstance();
      cal.setTime(ts);

      String msg = evt.getMessage();
      if (msg.length() >= MAX_MSG_LENGTH)
      {
         msg = msg.substring(0, MAX_MSG_LENGTH);
      }

      String stackTrace = "";
      IThrowableProxy throwableProxy = evt.getThrowableProxy();
      if (throwableProxy != null)
      {
         stackTrace = buildStackTrace(new StringBuilder(), throwableProxy, 1).toString();
      }

      String className = "unknown";
      String methodName = "unknown";
      int lineNumber = 0;
      if (evt.getCallerData() != null && evt.getCallerData().length > 0)
      {
         StackTraceElement ste = evt.getCallerData()[0];
         className = ste.getClassName();
         methodName = ste.getMethodName();
         lineNumber = ste.getLineNumber();
      }

      int i = 1;

      // dayId, dayKey, service, level, levelName, category, className, method, 
      // messageKey, message, error, machine, machineIp, messageNum, timestamp, lastModified
      int dayId = Integer.parseInt(dayIdFormat.format(ts));
      statement.setInt(i++, dayId);

      int dayKey = cal.get(Calendar.DAY_OF_WEEK);
      statement.setInt(i++, dayKey);

      statement.setString(i++, service); // service

      statement.setInt(i++, evt.getLevel().toInt());
      statement.setString(i++, evt.getLevel().toString());

      statement.setString(i++, evt.getLoggerName()); // category
      statement.setString(i++, className); // className
      statement.setString(i++, methodName); // method
      statement.setInt(i++, lineNumber); // lineNumber

      statement.setString(i++, getMessageKey(msg)); // messageKey
      statement.setString(i++, msg); // message

      statement.setString(i++, stackTrace); // error

      statement.setString(i++, buildVersion); // buildVersion
      statement.setString(i++, machine); // machine
      statement.setString(i++, machineIp); // machineIp

      statement.setInt(i++, nextMessageNum()); // messageNum
      statement.setTimestamp(i++, ts); // timestamp
      statement.setLong(i++, evt.getTimeStamp()); // lastModified

   }

   synchronized void push(ILoggingEvent event)
   {
      if (queue.size() >= maxQueue)
      {
         log.warn("Exceeding queue length.  LoggingEvent will be discarded" + event.getMessage());
         return;
      }

      queue.add(event);
      if (queue.size() >= 1)
         this.notifyAll();
   }

   synchronized List<ILoggingEvent> pop()
   {
      while (queue.size() == 0)
      {
         try
         {
            this.wait();
         }
         catch (Exception ex)
         {

         }
      }

      List<ILoggingEvent> oldQueue = queue;
      queue = new LinkedList<ILoggingEvent>();
      return oldQueue;
   }

   /**
    * This will attempt to load the META-INF/build-info.properties and use the build.buildtime property for the buildVersion
    * The build-info.properties file is generated by gradle at build time.
    */
   private void loadBuildInfo()
   {
      buildVersion = "unknown";
      try
      {
         Properties props = new Properties();
         props.load(this.getClass().getClassLoader().getResourceAsStream("META-INF/build-info.properties"));
         buildVersion = props.getProperty("build.buildtime");
      }
      catch (Exception e)
      {
         System.out.println("Warning buildVersion could not be found in LogbackDbAppender trying to use META-INF/build-info.properties");
      }
   }

   private void buildInsertSql()
   {
      insertSql = " INSERT INTO " + tableName + //
            " (`dayId`, `dayKey`, `service`, `level`, `levelName`, `category`, `className`, `method`, `lineNumber`, `messageKey`, "//
            + "`message`, `error`, `buildVersion`, `machine`, `machineIp`, `messageNum`, `timestamp`, `lastModified`) "//
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "//
            + "ON DUPLICATE KEY UPDATE "//
            + "dayId=VALUES(dayId), level=VALUES(level), levelName=VALUES(levelName), category=VALUES(category), className=VALUES(className), " //
            + "method=VALUES(method), lineNumber=VALUES(lineNumber), messageKey=VALUES(messageKey),message=VALUES(message), error=VALUES(error), " //
            + "buildVersion=VALUES(buildVersion), machine=VALUES(machine), timestamp=VALUES(timestamp), lastModified=VALUES(lastModified)";
   }

   /**
    * Attempts to create a key from a given message.
    * A key has a maximum length of MAX_MSGKEY_LENGTH
    * @param message
    * @return the shortest possible key or null if the message is null
    */
   private String getMessageKey(String message)
   {
      int MIN = 4;
      if (message == null)
         return null;

      if (message.length() >= MAX_MSGKEY_LENGTH)
      {
         message = message.substring(0, MAX_MSGKEY_LENGTH);
      }

      int endOfKeyIndex = message.length();
      endOfKeyIndex = findMessageKeyIndex(endOfKeyIndex, message, MIN, ". ");
      endOfKeyIndex = findMessageKeyIndex(endOfKeyIndex, message, MIN, "=");
      endOfKeyIndex = findMessageKeyIndex(endOfKeyIndex, message, MIN, ": ");
      endOfKeyIndex = findMessageKeyIndex(endOfKeyIndex, message, MIN, "-");
      endOfKeyIndex = findMessageKeyIndex(endOfKeyIndex, message, MIN, "\n");
      endOfKeyIndex = findMessageKeyIndex(endOfKeyIndex, message, MIN, "\t");
      endOfKeyIndex = findMessageKeyIndex(endOfKeyIndex, message, MIN, "**");

      if (endOfKeyIndex > 0)
      {
         return message.substring(0, endOfKeyIndex);
      }
      else
      {
         return message;
      }

   }

   private int findMessageKeyIndex(int oldIndex, String message, int minIndex, String character)
   {

      int i = message.indexOf(character);
      if (oldIndex > i && i > minIndex)
      {
         oldIndex = i;
      }

      return oldIndex;
   }

   private synchronized int nextMessageNum()
   {
      messageNum++;
      if (messageNum > maxMessagesPerDay)
      {
         messageNum = 1;
      }
      return messageNum;
   }

   private StringBuilder buildStackTrace(StringBuilder sb, IThrowableProxy throwableProxy, int level)
   {
      int maxLevel = 5;
      int standardLineLimit = 2;
      int causeLineLimit = 12;

      if (throwableProxy != null && level < maxLevel)
      {
         if (level > 1)
         {
            sb.append("Caused by: ");
         }
         sb.append(throwableProxy.getClassName()).append(": ").append(throwableProxy.getMessage()).append("\n");
         IThrowableProxy cause = throwableProxy.getCause();
         int i = 0;
         int limit = standardLineLimit;
         if (cause == null)
         {
            limit = causeLineLimit;
         }

         int totalLines = throwableProxy.getStackTraceElementProxyArray().length;
         for (StackTraceElementProxy stackLine : throwableProxy.getStackTraceElementProxyArray())
         {
            sb.append("\t").append(stackLine.toString()).append("\n");
            i++;
            totalLines--;
            if (i >= limit)
            {
               if (totalLines > 0)
               {
                  sb.append("\t... ").append(totalLines).append(" lines omitted \n");
               }
               break;
            }
         }

         if (cause != null)
         {
            buildStackTrace(sb, cause, ++level);
         }
      }

      if (sb.length() > MAX_STACK_LENGTH)
      {
         sb.delete(MAX_STACK_LENGTH, sb.length());
      }

      return sb;
   }

   public DataSource getDataSource()
   {
      return dataSource;
   }

   public void setDataSource(DataSource dataSource)
   {
      this.dataSource = dataSource;
   }

   public String getTableName()
   {
      return tableName;
   }

   public void setTableName(String tableName)
   {
      this.tableName = tableName;
   }

   public String getService()
   {
      return service;
   }

   public void setService(String service)
   {
      this.service = service;
   }

   public int getMaxMessagesPerDay()
   {
      return maxMessagesPerDay;
   }

   public void setMaxMessagesPerDay(int maxMessagesPerDay)
   {
      this.maxMessagesPerDay = maxMessagesPerDay;
   }

   public int getMaxQueue()
   {
      return maxQueue;
   }

   public void setMaxQueue(int maxQueue)
   {
      this.maxQueue = maxQueue;
   }

   private String getExampleUsage()
   {
      String example = "EXAMPLE:\n" + //
            " <appender name=\"DB\" class=\"com.liftck.util.logging.LogbackBatchDbAppender\">\n" + //
            "    <dataSource class=\"org.apache.tomcat.jdbc.pool.DataSource\">\n" + //
            "       <driverClassName>com.mysql.cj.jdbc.Driver</driverClassName>\n" + //
            "       <url>jdbc:mysql://dbhost:3306/tablename?useSSL=false&amp;rewriteBatchedStatements=true</url>\n" + //
            "       <username>root</username>\n" + //
            "       <password>password</password>\n" + //
            "       <maxActive>20</maxActive>\n" + //
            "    </dataSource\n" + //
            "    <tableName>ServerMessage</tableName>\n" + //
            "    <service>loyalty</service>\n" + //
            "    <maxMessagesPerDay>1000</maxMessagesPerDay>\n" + //
            " </appender>\n\n" + //
            " NOTE: if you use a different DataSource class parameter names may be different\n\n";
      return example;

   }
}
