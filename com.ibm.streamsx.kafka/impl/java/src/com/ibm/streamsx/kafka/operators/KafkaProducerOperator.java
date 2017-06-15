/* Generated by Streams Studio: April 6, 2017 at 3:30:27 PM EDT */
package com.ibm.streamsx.kafka.operators;

import com.ibm.streams.operator.model.Icons;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.PrimitiveOperator;

@PrimitiveOperator(name = "KafkaProducer", namespace = "com.ibm.streamsx.kafka", description = KafkaProducerOperator.DESC)
@InputPorts({ @InputPortSet(description = "This port consumes tuples to be written to the Kafka topic(s). Each tuple received on "
		+ "this port will be written to the Kafka topic(s).", cardinality = 1, optional = false) })
@Icons(location16 = "icons/KafkaProducer_16.gif", location32 = "icons/KafkaProducer_32.gif")
public class KafkaProducerOperator extends AbstractKafkaProducerOperator {

	public static final String DESC = 
			"The KafkaProducer operator is used to produce messages on Kafka "
			+ "topics. The operator can be configured to produce messages to "
			+ "one or more topics.\\n" + 
			"\\n" + 
			"# Kafka Properties\\n" + 
			"\\n" + 
			"The operator implements Kafka's KafkaProducer API. As a result, "
			+ "it supports all Kafka properties that are supported by the "
			+ "underlying API. Properties can be specified in a file or in an "
			+ "application configuration. If specifying properties via a file, "
			+ "the **propertiesFile** parameter can be used. If specifying properties "
			+ "in an application configuration, the name of the application configuration "
			+ "can be specified using the **appConfig** parameter.\\n" + 
			"\\n" + 
			"The only property that the user is required to set is the `bootstrap.servers` "
			+ "property, which points to the Kafka brokers. All other properties are optional. "
			+ "The operator sets some properties by default to enable users to quickly get "
			+ "started with the operator. The following lists which properties the operator "
			+ "sets by default: \\n" + 
			"\\n" + 
			"---\\n" + 
			"| Property name | Default Value |\\n" + 
			"|===|\\n" + 
			"| client.id | Randomly generated ID in the form: `producer-<random_string>` |\\n" + 
			"|---|\\n" + 
			"| key.serializer | See **Automatic Serialization** section below |\\n" + 
			"|---|\\n" + 
			"| value.serializer | See **Automatic Serialization** section below |\\n" + 
			"---\\n" + 
			"\\n" + 
			"**NOTE:** Users can override any of the above properties by explicitly setting "
			+ "the property value in either a properties file or in an application configuration. \\n" + 
			"\\n"
			+ "\\n"
			+ "# Kafka Properties via Application Configuration\\n" + 
			"\\n" + 
			"Users can specify Kafka properties using Streams' application configurations. Information "
			+ "on configuring application configurations can be found here: "
			+ "[https://www.ibm.com/support/knowledgecenter/SSCRJU_4.2.1/com.ibm.streams.admin.doc/doc/"
			+ "creating-secure-app-configs.html|Creating application configuration objects to securely "
			+ "store data]. Each property set in the application configuration "
			+ "will be loaded as a Kafka property. For example, to specify the bootstrap servers that "
			+ "the operator should connect to, an app config property named `bootstrap.servers` should "
			+ "be created.\\n"
			+ "\\n" + 
			"# Automatic Serialization\\n" + 
			"\\n" + 
			"The operaotr will automatically select the appropriate serializers for the key "
			+ "and message based on their types. The following table outlines which "
			+ "deserializer will be used given a particular type: \\n" + 
			"\\n" + 
			"---\\n" + 
			"| Serializer | SPL Types |\\n" + 
			"|===|\\n" + 
			"| org.apache.kafka.common.serialization.StringSerializer | rstring |\\n" + 
			"|---|\\n" + 
			"| org.apache.kafka.common.serialization.IntegerSerializer | int32, uint32 |\\n" + 
			"|---|\\n" + 
			"| org.apache.kafka.common.serialization.LongSerializer | int64, uint64 |\\n" + 
			"|---|\\n" + 
			"| org.apache.kafka.common.serialization.DoubleSerializer | float64 |\\n" + 
			"|---|\\n" + 
			"| org.apache.kafka.common.serialization.ByteArraySerializer | blob |\\n" + 
			"---\\n" + 
			"\\n"
			+ "\\n" +			
			"# Consistent Region Strategy\\n" + 
			"\\n" + 
			"The `KafkaProducer` operator can participate in a consistent region. The operator "
			+ "cannot be the start of a consistent region. The operator supports 'at least once' "
			+ "delivery semantics. If the operator crashes or is reset while in a consistent "
			+ "region, the operator will write all tuples replayed. This ensures that every "
			+ "tuple sent to the operator will be written to the topic(s). However, 'at least once' "
			+ "semantics implies that duplicate messages may be written to the topic(s). \\n" + 
			"\\n" + 
			"\\n" + 
			"# Error Handling\\n" + 
			"\\n" + 
			"Many exceptions thrown by the underlying Kafka API are considered fatal. In the event "
			+ "that Kafka throws an exception, the operator will restart. Some exceptions can be "
			+ "retried, such as those that occur due to network error. Users are encouraged "
			+ "to set the KafkaProducer `retries` property to a value greater than 0 to enable the producer's "
			+ "retry mechanism. \\n" + 
			"\\n" + 
			"";
	
}