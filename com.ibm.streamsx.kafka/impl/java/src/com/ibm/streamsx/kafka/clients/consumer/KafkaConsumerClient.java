package com.ibm.streamsx.kafka.clients.consumer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.log4j.Logger;

import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.control.ControlPlaneContext;
import com.ibm.streams.operator.control.variable.ControlVariableAccessor;
import com.ibm.streams.operator.state.Checkpoint;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streamsx.kafka.clients.AbstractKafkaClient;
import com.ibm.streamsx.kafka.clients.OffsetManager;
import com.ibm.streamsx.kafka.clients.consumer.Event.EventType;
import com.ibm.streamsx.kafka.properties.KafkaOperatorProperties;

public class KafkaConsumerClient extends AbstractKafkaClient {

    private static final Logger logger = Logger.getLogger(KafkaConsumerClient.class);
    private static final long EVENT_LOOP_PAUSE_TIME = 100;
    private static final long CONSUMER_TIMEOUT_MS = 2000;
    private static final int MESSAGE_QUEUE_SIZE = 100;
    private static final String GENERATED_GROUPID_PREFIX = "group-"; //$NON-NLS-1$
    private static final String GENERATED_CLIENTID_PREFIX = "client-"; //$NON-NLS-1$

    private KafkaConsumer<?, ?> consumer;
    private OffsetManager offsetManager;

    private KafkaOperatorProperties kafkaProperties;
    private ControlVariableAccessor<String> offsetManagerCV;

    private BlockingQueue<ConsumerRecords<?, ?>> messageQueue;
    private BlockingQueue<Event> eventQueue;
    private AtomicBoolean processing;

    private CountDownLatch consumerStartedLatch;
    private CountDownLatch checkpointingLatch;
    private CountDownLatch resettingLatch;
    private CountDownLatch shutdownLatch;
    private CountDownLatch pollingStoppedLatch;
    private CountDownLatch updateAssignmentLatch;
    private ConsistentRegionContext crContext;
    private Collection<Integer> partitions;
    private boolean isAssignedToTopics;
    
    private Thread eventThread;

    private <K, V> KafkaConsumerClient(OperatorContext operatorContext, Class<K> keyClass, Class<V> valueClass,
            KafkaOperatorProperties kafkaProperties)
                    throws Exception {
        this.kafkaProperties = kafkaProperties;
        if (!this.kafkaProperties.containsKey(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)) {
            this.kafkaProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, getDeserializer(keyClass));
        }

        if (!kafkaProperties.containsKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)) {
            this.kafkaProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, getDeserializer(valueClass));
        }

        // create a random group ID for the consumer if one is not specified
        if (!kafkaProperties.containsKey(ConsumerConfig.GROUP_ID_CONFIG)) {
            this.kafkaProperties.put(ConsumerConfig.GROUP_ID_CONFIG, getRandomId(GENERATED_GROUPID_PREFIX));
        }

        // Create a random client ID for the consumer if one is not specified.
        // This is important, otherwise running multiple consumers from the same
        // application will result in a KafkaException when registering the
        // client
        if (!kafkaProperties.containsKey(ConsumerConfig.CLIENT_ID_CONFIG)) {
            this.kafkaProperties.put(ConsumerConfig.CLIENT_ID_CONFIG, getRandomId(GENERATED_CLIENTID_PREFIX));
        }

        messageQueue = new LinkedBlockingQueue<ConsumerRecords<?, ?>>(MESSAGE_QUEUE_SIZE);
        eventQueue = new LinkedBlockingQueue<Event>();
        processing = new AtomicBoolean(false);
        crContext = operatorContext.getOptionalContext(ConsistentRegionContext.class);
        this.partitions = partitions == null ? Collections.emptyList() : partitions;
        
        consumerStartedLatch = new CountDownLatch(1);
        eventThread = operatorContext.getThreadFactory().newThread(new Runnable() {

            @Override
            public void run() {
                try {
                    consumer = new KafkaConsumer<>(kafkaProperties);
                    offsetManager = new OffsetManager(consumer);

                    // create control variables
                    if(crContext != null) {
                        ControlPlaneContext controlPlaneContext = operatorContext
                                .getOptionalContext(ControlPlaneContext.class);
                        offsetManagerCV = controlPlaneContext.createStringControlVariable(OffsetManager.class.getName(),
                                false, serializeObject(offsetManager));	
                    }

                    consumerStartedLatch.countDown(); // consumer is ready
                    startEventLoop();
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.error(e.getLocalizedMessage(), e);
                    throw new RuntimeException(e);
                }
            }
        });
        eventThread.setDaemon(false);
        
        eventThread.start();
        consumerStartedLatch.await(); // wait for consumer to be created before returning
    }

    private List<TopicPartition> getAllTopicPartitionsForTopic(Collection<String> topics) {
    	List<TopicPartition> topicPartitions = new ArrayList<TopicPartition>();
		topics.forEach(topic -> {
			List<PartitionInfo> partitions = consumer.partitionsFor(topic);
			partitions.forEach(p -> topicPartitions.add(new TopicPartition(topic, p.partition())));
		});
		
		return topicPartitions;
    }
    
    public void subscribeToTopics(Collection<String> topics, Collection<Integer> partitions, StartPosition startPosition) {
    	logger.debug("subscribeToTopics: topics=" + topics + ", partitions=" + partitions + ", startPosition=" + startPosition);
    	assert startPosition != StartPosition.Time;
    	
    	if(topics != null && !topics.isEmpty()) {
    		if(partitions == null || partitions.isEmpty()) {
    			// no partition information provided
    			if(startPosition == StartPosition.Default) {
    				subscribe(topics);	
    			} else {
        			List<TopicPartition> topicPartitions = getAllTopicPartitionsForTopic(topics);
        			assign(topicPartitions);
        			seekToPosition(topicPartitions, startPosition);    				
    			}    			
    		} else {
    			List<TopicPartition> topicPartitions = new ArrayList<TopicPartition>();
    	    	topics.forEach(topic -> {
    	    		partitions.forEach(partition -> topicPartitions.add(new TopicPartition(topic, partition)));
    	    	});
    	    	
    	    	assign(topicPartitions);
    	    	
    	    	if(startPosition != StartPosition.Default) {
        	    	seekToPosition(topicPartitions, startPosition);    	    		
    	    	}
    		}
    		
          if (crContext != null) {
	          // save the consumer offset after moving it's position
	          offsetManager.savePositionFromCluster();
          }
    	}
    }

    public void subscribeToTopicsWithTimestamp(Collection<String> topics, Collection<Integer> partitions, Long timestamp) {
    	logger.debug("subscribeToTopicsWithTimestamp: topic=" + topics + ", partitions=" + partitions + ", timestamp=" + timestamp);
    	Map<TopicPartition, Long /* timestamp */> topicPartitionTimestampMap = new HashMap<TopicPartition, Long>();
    	if(partitions == null || partitions.isEmpty()) {
    		List<TopicPartition> topicPartitions = getAllTopicPartitionsForTopic(topics);
    		topicPartitions.forEach(tp -> topicPartitionTimestampMap.put(tp, timestamp));
    	} else {
    		topics.forEach(topic -> {
    			partitions.forEach(partition -> topicPartitionTimestampMap.put(new TopicPartition(topic, partition), timestamp));
    		});
    	}

    	assign(topicPartitionTimestampMap.keySet());
    	seekToTimestamp(topicPartitionTimestampMap);
    	
    	if (crContext != null) {
    		// save the consumer offset after moving it's position
    		offsetManager.savePositionFromCluster();
    	}
    }
    
    public void subscribeToTopicsWithOffsets(Map<TopicPartition, Long> topicPartitionOffsetMap) {
    	logger.debug("subscribeToTopicsWithOffsets: topicPartitionOffsetMap=" + topicPartitionOffsetMap);
    	if(topicPartitionOffsetMap != null && !topicPartitionOffsetMap.isEmpty()) {
    		assign(topicPartitionOffsetMap.keySet());
    	}
    	
    	// seek to position
    	seekToOffset(topicPartitionOffsetMap);
    	
    	if (crContext != null) {
    		// save the consumer offset after moving it's position
    		offsetManager.savePositionFromCluster();
    	}
    }

    private void subscribe(Collection<String> topics) {
        logger.debug("Subscribing: topics=" + topics); //$NON-NLS-1$
        consumer.subscribe(topics);
        isAssignedToTopics = true;
    }
    
    private void assign(Collection<TopicPartition> topicPartitions) {
    	Map<String /* topic */, List<TopicPartition>> topicPartitionMap = new HashMap<>();

    	// update the offsetmanager
    	topicPartitions.forEach(tp -> {
    		if(!topicPartitionMap.containsKey(tp.topic())) {
    			topicPartitionMap.put(tp.topic(), new ArrayList<>());
    		}
    		topicPartitionMap.get(tp.topic()).add(tp);
    	});
    	topicPartitionMap.forEach((topic, tpList) -> offsetManager.addTopic(topic, tpList));
    	
    	consumer.assign(topicPartitions);
    	isAssignedToTopics = true;
    }
    
    private void seekToPosition(Collection<TopicPartition> topicPartitions, StartPosition startPosition) {
    	if(startPosition == StartPosition.Beginning) {
    		consumer.seekToBeginning(topicPartitions);
    	} else if(startPosition == StartPosition.End){
    		consumer.seekToEnd(topicPartitions);
    	}
    }
    
    /*
     * If offset equals -1, seek to the end of the topic
     * If offset equals -2, seek to the beginning of the topic
     * Otherwise, seek to the specified offset
     */
    private void seekToOffset(Map<TopicPartition, Long> topicPartitionOffsetMap) {
    	topicPartitionOffsetMap.forEach((tp, offset) -> {
    		if(offset == -1l) {
    			consumer.seekToEnd(Arrays.asList(tp));
    		} else if(offset == -2) {
    			consumer.seekToBeginning(Arrays.asList(tp));
    		} else {
    			consumer.seek(tp, offset);	
    		}
    	});
    }

    private void seekToTimestamp(Map<TopicPartition, Long> topicPartitionTimestampMap) {
    	Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = consumer.offsetsForTimes(topicPartitionTimestampMap);
    	logger.debug("offsetsForTimes=" + offsetsForTimes);
    	
    	topicPartitionTimestampMap.forEach((tp, timestamp) -> {
    		OffsetAndTimestamp ot = offsetsForTimes.get(tp);
    		if(ot != null) {
        		logger.debug("Seeking consumer for tp=" + tp + " to offsetAndTimestamp=" + ot);
        		consumer.seek(tp, ot.offset());
    		} else {
    			// nothing...consumer will move to the offset as determined by the 'auto.offset.reset' config
    		}
    	});
    }
    
//    private void subscribeToTopics(Collection<String> topics, Collection<Integer> partitions, 
//    		StartPosition startPosition, Long startTime) {
//    	if(topics != null && !topics.isEmpty()) {
//    	    // Kafka's group management feature can only be used in the following
//            // scenarios:
//            // - startPosition = End
//            // - none of the topics have partitions assignments
//            // - operator is not in a consistent region
//
//            if (startPosition != StartPosition.End || 
//            		crContext != null ||
//            		(partitions != null && !partitions.isEmpty())) {
//                assign(topics, partitions, startPosition, startTime);
//            } else {
//            	subscribe(topics);
//            }
//            
//            isAssignedToTopics = true;
//    	}
//    }
//    
//    /*
//     * Assigns the consumer to all partitions for each of the topics. To assign
//     */
//    private void assign(Collection<String> topics, Collection<Integer> partitions, 
//    		StartPosition startPosition, Long startTime) {
//        logger.debug("Assigning: topics=" + topics + ", partitions=" + partitions + ", startPosition=" + startPosition + ", startTime=" + startTime); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
//        
//        List<TopicPartition> topicPartitions = new ArrayList<TopicPartition>();
//        topics.forEach(topic -> {
//        	List<TopicPartition> theseTopicPartitions = new ArrayList<>();
//        	if(partitions != null) {
//        		// assign the consumer to specific partitions within a topic
//            	partitions.forEach(partition -> theseTopicPartitions.add(new TopicPartition(topic, partition)));	
//        	} else {
//        		// assign the consumer to all partitions within a topic
//        		consumer.partitionsFor(topic).forEach(p -> theseTopicPartitions.add(new TopicPartition(topic, p.partition())));        		
//        	}
//        	topicPartitions.addAll(theseTopicPartitions);
//        	offsetManager.addTopic(topic, theseTopicPartitions);
//        });
//        consumer.assign(topicPartitions);
//
//        // passing in an empty list means seek to
//        // the beginning/end of ALL assigned partitions
//        switch (startPosition) {
//        case Beginning:
//            consumer.seekToBeginning(Collections.emptyList());
//            break;
//        case End:
//            consumer.seekToEnd(Collections.emptyList());
//            break;
//        case Time:
//        	Map<TopicPartition, Long> timestampsToSearch = new HashMap<>();
//        	topicPartitions.forEach(tp -> timestampsToSearch.put(tp, startTime));
//        	logger.debug("timestampToSearch=" + timestampsToSearch);
//        	Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = consumer.offsetsForTimes(timestampsToSearch);
//        	logger.debug("offsetsForTimes=" + offsetsForTimes);
//
//        	topicPartitions.forEach(tp -> {
//        		OffsetAndTimestamp ot = offsetsForTimes.get(tp);
//        		if(ot != null) {
//            		logger.debug("Seeking consumer for tp=" + tp + " to offsetAndTimestamp=" + ot);
//            		consumer.seek(tp, ot.offset());
//        		} else {
//        			logger.debug("Seeking consumer to end of TopicPartition for tp=" + tp);
//        			consumer.seekToEnd(Arrays.asList(tp));
//        		}
//        	});
//        }
//
//        if (crContext != null) {
//            // save the consumer offset after moving it's position
//            // consumer.commitSync();
//            offsetManager.savePositionFromCluster();
//        }
//    }

    private void poll(long timeout) throws Exception {
        logger.debug("Initiating polling..."); //$NON-NLS-1$
        // continue polling for messages until a new event
        // arrives in the event queue
        while (eventQueue.isEmpty()) {
            if (messageQueue.remainingCapacity() > 0) {
                logger.trace("Polling for records..."); //$NON-NLS-1$
                try {
                    ConsumerRecords<?, ?> records = consumer.poll(timeout);
                    if (records != null) {
                        records.forEach(cr -> logger.debug(cr.key() + " - offset=" + cr.offset())); //$NON-NLS-1$
                        messageQueue.add(records);
                    }
                } catch (SerializationException e) {
                    // cannot do anything else at the moment
                    // (may be possible to handle this in future Kafka release
                    // (v0.11)
                    // https://issues.apache.org/jira/browse/KAFKA-4740)
                    throw e;
                }
            } else {
                // prevent busy-wait
                Thread.sleep(100);
            }
        }
        logger.debug("Stop polling, message in event queue: " + eventQueue.peek().getEventType()); //$NON-NLS-1$
    }

    public boolean isAssignedToTopics() {
    	return isAssignedToTopics;
    }
    
    public void startEventLoop() throws Exception {
        logger.debug("Event loop started!"); //$NON-NLS-1$
        processing.set(true);
        while (processing.get()) {
            logger.debug("Checking event queue for message..."); //$NON-NLS-1$
            Event event = eventQueue.poll(1, TimeUnit.SECONDS);

            if (event == null) {
                Thread.sleep(EVENT_LOOP_PAUSE_TIME);
                continue;
            }

            logger.debug("Received event: " + event.getEventType().name()); //$NON-NLS-1$
            switch (event.getEventType()) {
            case START_POLLING:
                poll((Long) event.getData());
                break;
            case STOP_POLLING:
                pollingStoppedLatch.countDown(); // indicates that polling has stopped
                break;
            case UPDATE_ASSIGNMENT:
            	updateAssignment(event.getData());
            	break;
            case CHECKPOINT:
                checkpoint((Checkpoint) event.getData());
                break;
            case RESET:
                reset((Checkpoint) event.getData());
                break;
            case RESET_TO_INIT:
                resetToInitialState();
                break;
            case SHUTDOWN:
                shutdown();
            default:
                Thread.sleep(EVENT_LOOP_PAUSE_TIME);
                break;
            }
        }
    }

    private void updateAssignment(Object data) {
    	try {
    		TopicPartitionUpdate update = (TopicPartitionUpdate)data;
    		
    		// get a map of current topic partitions and their offsets
    		Map<TopicPartition, Long /* offset */> currentTopicPartitionOffsets = new HashMap<TopicPartition, Long>();
    		
    		Set<TopicPartition> topicPartitions = consumer.assignment();
    		topicPartitions.forEach(tp -> currentTopicPartitionOffsets.put(tp, consumer.position(tp)));
    		
    		if(update.getAction() == TopicPartitionUpdateAction.ADD) {
    			update.getTopicPartitionOffsetMap().forEach((tp, offset) -> {
    				currentTopicPartitionOffsets.put(tp, offset);
    			});
    		} else if(update.getAction() == TopicPartitionUpdateAction.REMOVE) {
    			update.getTopicPartitionOffsetMap().forEach((tp, offset) -> {
    				currentTopicPartitionOffsets.remove(tp);
    			});
    		}
    		
    		subscribeToTopicsWithOffsets(currentTopicPartitionOffsets);	
    	} finally {
        	updateAssignmentLatch.countDown();
    	}
	}

	public void sendStartPollingEvent(long timeout) {
        logger.debug("Sending " + EventType.START_POLLING.name() + " event..."); //$NON-NLS-1$ //$NON-NLS-2$
        eventQueue.add(new Event(EventType.START_POLLING, Long.valueOf(timeout)));
    }

    public void sendStopPollingEvent() throws Exception {
        logger.debug("Sending " + EventType.STOP_POLLING + " event..."); //$NON-NLS-1$ //$NON-NLS-2$
        pollingStoppedLatch = new CountDownLatch(1);
        eventQueue.add(new Event(EventType.STOP_POLLING, null));
        pollingStoppedLatch.await();
    }

    public void sendUpdateTopicAssignmentEvent(TopicPartitionUpdate update) throws Exception {
    	logger.debug("Sending " + EventType.UPDATE_ASSIGNMENT + " event...");
    	updateAssignmentLatch = new CountDownLatch(1);
    	eventQueue.add(new Event(EventType.UPDATE_ASSIGNMENT, update));
    	updateAssignmentLatch.await();
    }
    
    public void sendCheckpointEvent(Checkpoint checkpoint) throws Exception {
        logger.debug("Sending " + EventType.CHECKPOINT + " event..."); //$NON-NLS-1$ //$NON-NLS-2$
        checkpointingLatch = new CountDownLatch(1);
        eventQueue.add(new Event(EventType.CHECKPOINT, checkpoint));
        checkpointingLatch.await();
    }

    public void sendResetEvent(Checkpoint checkpoint) throws Exception {
        logger.debug("Sending " + EventType.RESET + " event..."); //$NON-NLS-1$ //$NON-NLS-2$
        resettingLatch = new CountDownLatch(1);
        eventQueue.add(new Event(EventType.RESET, checkpoint));
        resettingLatch.await();
    }

    public void sendResetToInitEvent() throws Exception {
        logger.debug("Sending " + EventType.RESET_TO_INIT + " event..."); //$NON-NLS-1$ //$NON-NLS-2$
        resettingLatch = new CountDownLatch(1);
        eventQueue.add(new Event(EventType.RESET_TO_INIT, null));
        resettingLatch.await();
    }

    public void sendShutdownEvent(long timeout, TimeUnit timeUnit) throws Exception {
        logger.debug("Sending " + EventType.SHUTDOWN + " event..."); //$NON-NLS-1$ //$NON-NLS-2$
        shutdownLatch = new CountDownLatch(1);
        eventQueue.add(new Event(EventType.SHUTDOWN, null));
        shutdownLatch.await(timeout, timeUnit);
    }

    public ConsumerRecords<?, ?> getRecords() {
        try {
            return messageQueue.poll(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Consumer interrupted while waiting for messages to arrive", e); //$NON-NLS-1$
        }
    }

    private void refreshFromCluster() {
        logger.debug("Refreshing from cluster..."); //$NON-NLS-1$
        List<String> topics = offsetManager.getTopics();
        Map<TopicPartition, Long> startOffsetMap = new HashMap<TopicPartition, Long>();
        for (String topic : topics) {
            List<PartitionInfo> parts = consumer.partitionsFor(topic);
            parts.forEach(pi -> {
            	// if the 'partitions' list is empty, retrieve offsets for all topic partitions,
            	// otherwise only retrieve offsets for the user-specified partitions
            	if(partitions.isEmpty() || partitions.contains(pi.partition())) {
                    TopicPartition tp = new TopicPartition(pi.topic(), pi.partition());
                    long startOffset = offsetManager.getOffset(pi.topic(), pi.partition());
                    if(startOffset > -1l) {
                    	startOffsetMap.put(tp, startOffset);
                    }
            	}
            });
        }
        logger.debug("startOffsets=" + startOffsetMap); //$NON-NLS-1$

        // assign the consumer to the partitions and seek to the
        // last saved offset
        consumer.assign(startOffsetMap.keySet());
        for (Entry<TopicPartition, Long> entry : startOffsetMap.entrySet()) {
            logger.debug("Consumer seeking: TopicPartition=" + entry.getKey() + ", new_offset=" + entry.getValue()); //$NON-NLS-1$ //$NON-NLS-2$

            consumer.seek(entry.getKey(), entry.getValue());
        }
    }

    private void shutdown() {
        logger.debug("Shutdown sequence started..."); //$NON-NLS-1$
        try {
            consumer.close(CONSUMER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            processing.set(false);
        } finally {
            shutdownLatch.countDown();
        }

    }

    public void drain() throws Exception {
        // nothing to drain
    }

    private void checkpoint(Checkpoint checkpoint) throws Exception {
        logger.debug("Checkpointing seq=" + checkpoint.getSequenceId()); //$NON-NLS-1$
        try {
            offsetManager.savePositionFromCluster();
            checkpoint.getOutputStream().writeObject(offsetManager);
            logger.debug("offsetManager=" + offsetManager); //$NON-NLS-1$
        } finally {
            checkpointingLatch.countDown();
        }

    }

    private void reset(Checkpoint checkpoint) throws Exception {
        logger.debug("Resetting to seq=" + checkpoint.getSequenceId()); //$NON-NLS-1$
        try {
            offsetManager = (OffsetManager) checkpoint.getInputStream().readObject();
            offsetManager.setOffsetConsumer(consumer);

            refreshFromCluster();
        } finally {
            resettingLatch.countDown();
        }
    }

    private void resetToInitialState() throws Exception {
        logger.debug("Resetting to initial state..."); //$NON-NLS-1$
        try {
            offsetManager = SerializationUtils
                    .deserialize(Base64.getDecoder().decode(offsetManagerCV.sync().getValue()));
            offsetManager.setOffsetConsumer(consumer);
            logger.debug("offsetManager=" + offsetManager); //$NON-NLS-1$

            // refresh from the cluster as we may
            // have written to the topics
            refreshFromCluster();
        } finally {
            resettingLatch.countDown();
        }
    }
    
    public static class KafkaConsumerClientBuilder {
    	private OperatorContext operatorContext;
    	private Class<?> keyClass;
    	private Class<?> valueClass;
        private KafkaOperatorProperties kafkaProperties;
        
        public KafkaConsumerClientBuilder setKafkaProperties(KafkaOperatorProperties kafkaProperties) {
			this.kafkaProperties = kafkaProperties;
			return this;
		}
        
        public KafkaConsumerClientBuilder setKeyClass(Class<?> keyClass) {
			this.keyClass = keyClass;
			return this;
		}
        
        public KafkaConsumerClientBuilder setOperatorContext(OperatorContext operatorContext) {
			this.operatorContext = operatorContext;
			return this;
		}
        
        public KafkaConsumerClientBuilder setValueClass(Class<?> valueClass) {
			this.valueClass = valueClass;
			return this;
		}
        
        public KafkaConsumerClient build() throws Exception {
        	return new KafkaConsumerClient(operatorContext, keyClass, valueClass, kafkaProperties);
        }
    }
}
