/**
 * 
 */
package org.apache.phoenix.hive.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.phoenix.hive.constants.PhoenixStorageHandlerConstants;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;

/**
 * @author 주정민
 *
 */
public class PhoenixConnectionUtil {

	private static final Log LOG = LogFactory.getLog(PhoenixConnectionUtil.class);
	
	public static Connection getInputConnection(final Configuration conf, final Properties props) throws SQLException {
		String quorum = conf.get(PhoenixStorageHandlerConstants.ZOOKEEPER_QUORUM);
		quorum = quorum == null ? props.getProperty(PhoenixStorageHandlerConstants.ZOOKEEPER_QUORUM, PhoenixStorageHandlerConstants.DEFAULT_ZOOKEEPER_QUORUM) : quorum;
		
		int zooKeeperClientPort = conf.getInt(PhoenixStorageHandlerConstants.ZOOKEEPER_PORT, 0);
		zooKeeperClientPort = zooKeeperClientPort == 0 ? 
				Integer.parseInt(props.getProperty(PhoenixStorageHandlerConstants.ZOOKEEPER_PORT, String.valueOf(PhoenixStorageHandlerConstants.DEFAULT_ZOOKEEPER_PORT))) : zooKeeperClientPort;
		
		String zNodeParent = conf.get(PhoenixStorageHandlerConstants.ZOOKEEPER_PARENT);
		zNodeParent = zNodeParent == null ? props.getProperty(PhoenixStorageHandlerConstants.ZOOKEEPER_PARENT, PhoenixStorageHandlerConstants.DEFAULT_ZOOKEEPER_PARENT) : zNodeParent;
		
        return getConnection(quorum, zooKeeperClientPort, zNodeParent, PropertiesUtil.extractProperties(props, conf));
    }
	
	public static Connection getConnection(final Table table) throws SQLException {
		Map<String, String> tableParameterMap = table.getParameters();
		
		String zookeeperQuorum = tableParameterMap.get(PhoenixStorageHandlerConstants.ZOOKEEPER_QUORUM);
		zookeeperQuorum = zookeeperQuorum == null ? PhoenixStorageHandlerConstants.DEFAULT_ZOOKEEPER_QUORUM : zookeeperQuorum;
		
		String clientPortString = tableParameterMap.get(PhoenixStorageHandlerConstants.ZOOKEEPER_PORT);
		int clientPort = clientPortString == null ? PhoenixStorageHandlerConstants.DEFAULT_ZOOKEEPER_PORT : Integer.parseInt(clientPortString);
		
		String zNodeParent = tableParameterMap.get(PhoenixStorageHandlerConstants.ZOOKEEPER_PARENT);
		zNodeParent = zNodeParent == null ? PhoenixStorageHandlerConstants.DEFAULT_ZOOKEEPER_PARENT : zNodeParent;
		
		return DriverManager.getConnection(QueryUtil.getUrl(zookeeperQuorum, clientPort, zNodeParent));
    }
	
	private static Connection getConnection(final String quorum, final Integer clientPort, String zNodeParent, Properties props) throws SQLException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< [quorum, port, znode] : " + quorum + ", " + clientPort + ", " + zNodeParent + " >>>>>>>>>>");
		}
		
        return DriverManager.getConnection(clientPort != null ? QueryUtil.getUrl(quorum, clientPort, zNodeParent) :  QueryUtil.getUrl(quorum), props);
    }

}
