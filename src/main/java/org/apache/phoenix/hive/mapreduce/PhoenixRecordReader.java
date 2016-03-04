/**
 * 
 */
package org.apache.phoenix.hive.mapreduce;

import static org.apache.phoenix.monitoring.MetricType.SCAN_BYTES;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.coprocessor.BaseScannerRegionObserver;
import org.apache.phoenix.hive.PhoenixRowKey;
import org.apache.phoenix.hive.util.PhoenixStorageHandlerUtil;
import org.apache.phoenix.iterate.ConcatResultIterator;
import org.apache.phoenix.iterate.LookAheadResultIterator;
import org.apache.phoenix.iterate.PeekingResultIterator;
import org.apache.phoenix.iterate.ResultIterator;
import org.apache.phoenix.iterate.RoundRobinResultIterator;
import org.apache.phoenix.iterate.SequenceResultIterator;
import org.apache.phoenix.iterate.TableResultIterator;
import org.apache.phoenix.jdbc.PhoenixResultSet;
import org.apache.phoenix.monitoring.ReadMetricQueue;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

/**
 * @author 주정민
 *
 */
@SuppressWarnings("rawtypes")
public class PhoenixRecordReader<T extends DBWritable> implements RecordReader<WritableComparable, T> {

	private static final Log LOG = LogFactory.getLog(PhoenixRecordReader.class);
	
    private final Configuration  configuration;
    private final QueryPlan queryPlan;
//    private NullWritable key =  NullWritable.get();
    private WritableComparable key;
    private T value = null;
    private Class<T> inputClass;
//    private Constructor<T> constructor;
    private ResultIterator resultIterator = null;
    private PhoenixResultSet resultSet;
    private long readCount;
    
    private boolean isTransactional;
    
	public PhoenixRecordReader(Class<T> inputClass,final Configuration configuration,final QueryPlan queryPlan) throws IOException {
        this.inputClass = inputClass;
        this.configuration = configuration;
        this.queryPlan = queryPlan;
        
        isTransactional = PhoenixStorageHandlerUtil.isTransactionalTable(configuration);
    }

	public void initialize(InputSplit split) throws IOException {
		final PhoenixInputSplit pSplit = (PhoenixInputSplit) split;
		final List<Scan> scans = pSplit.getScans();
		
		if (LOG.isInfoEnabled()) {
			LOG.info("<<<<<<<<<< target table : " + queryPlan.getTableRef().getTable().getPhysicalName() + " >>>>>>>>>>");
		}
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("<<<<<<<<<< scan count[" + scans.size() + "] : " + Bytes.toStringBinary(scans.get(0).getStartRow()) + " ~ " +  Bytes.toStringBinary(scans.get(scans.size() - 1).getStopRow()) + " >>>>>>>>>>"); 
			LOG.debug("<<<<<<<<<< scan : " + scans.get(0) + " >>>>>>>>>>");
			LOG.debug("<<<<<<<<<< scanAttribute : " + scans.get(0).getAttributesMap() + " >>>>>>>>>>");
			
			for (int i = 0, limit = scans.size(); i < limit; i++) {
				LOG.debug("<<<<<<<<<< EXPECTED_UPPER_REGION_KEY[" + i + "] : " + Bytes.toStringBinary(scans.get(i).getAttribute(BaseScannerRegionObserver.EXPECTED_UPPER_REGION_KEY)) + " >>>>>>>>>>");
			}
		}
		
		try {
			List<PeekingResultIterator> iterators = Lists.newArrayListWithExpectedSize(scans.size());
			StatementContext ctx = queryPlan.getContext();
			ReadMetricQueue readMetrics = ctx.getReadMetricsQueue();
			String tableName = queryPlan.getTableRef().getTable().getPhysicalName().getString();
			for (Scan scan : scans) {
				final TableResultIterator tableResultIterator = new TableResultIterator(queryPlan.getContext(),
						queryPlan.getTableRef(), scan, readMetrics.allotMetric(SCAN_BYTES, tableName));
				PeekingResultIterator peekingResultIterator = LookAheadResultIterator.wrap(tableResultIterator);
				iterators.add(peekingResultIterator);
			}
			ResultIterator iterator = queryPlan.useRoundRobinIterator()
					? RoundRobinResultIterator.newIterator(iterators, queryPlan)
					: ConcatResultIterator.newIterator(iterators);
			if (queryPlan.getContext().getSequenceManager().getSequenceCount() > 0) {
				iterator = new SequenceResultIterator(iterator, queryPlan.getContext().getSequenceManager());
			}
			this.resultIterator = iterator;
			// Clone the row projector as it's not thread safe and would be used
			// simultaneously by
			// multiple threads otherwise.
			this.resultSet = new PhoenixResultSet(this.resultIterator, queryPlan.getProjector().cloneIfNecessary(),
					queryPlan.getContext());
		} catch (SQLException e) {
			LOG.error(String.format(" Error [%s] initializing PhoenixRecordReader. ", e.getMessage()));
			Throwables.propagate(e);
		}
	}

	@Override
	public boolean next(WritableComparable key, T value) throws IOException {
        try {
            if(!resultSet.next()) {
                return false;
            }
            value.readFields(resultSet);
            
            if (isTransactional) {
            	((PhoenixResultWritable)value).readPrimaryKey((PhoenixRowKey)key);
            }
            
            ++readCount;

            if (LOG.isTraceEnabled()) {
            	LOG.trace("<<<<<<<<<< result[" + readCount + "] : " + ((PhoenixResultWritable)value).getResultMap() + " >>>>>>>>>>");
            }
            
            return true;
        } catch (SQLException e) {
            LOG.error(String.format(" Error [%s] occurred while iterating over the resultset. ",e.getMessage()));
            throw new RuntimeException(e);
        }
	}

	@Override
	public WritableComparable createKey() {
		if (isTransactional) {
			key = new PhoenixRowKey();			
		} else {
			key = NullWritable.get();
		}
		
		return key;
	}

	@Override
	public T createValue() {
		value =  ReflectionUtils.newInstance(inputClass, this.configuration);
		return value;
	}

	@Override
	public long getPos() throws IOException {
		return 0;
	}

	@Override
	public void close() throws IOException {
		if (LOG.isInfoEnabled()) {
			LOG.info("<<<<<<<<<< Read Count : " + readCount + " >>>>>>>>>>");
		}
		
		if (resultIterator != null) {
			try {
				resultIterator.close();
			} catch (SQLException e) {
				LOG.error(" Error closing resultset.");
				throw new RuntimeException(e);
			}
		}
		
	}

	@Override
	public float getProgress() throws IOException {
		return 0;
	}

}
