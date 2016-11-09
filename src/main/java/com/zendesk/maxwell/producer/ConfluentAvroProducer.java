package com.zendesk.maxwell.producer;

import com.zendesk.maxwell.MaxwellContext;
import com.zendesk.maxwell.producer.partitioners.MaxwellKafkaPartitioner;
import com.zendesk.maxwell.replication.BinlogPosition;
import com.zendesk.maxwell.row.RowMap;
import com.zendesk.maxwell.row.RowMap.KeyFormat;
import com.zendesk.maxwell.schema.ddl.DDLMap;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Properties;

public class ConfluentAvroProducer extends AbstractProducer {
    static final Logger LOGGER = LoggerFactory.getLogger(ConfluentAvroProducer.class);

    private final InflightMessageList inflightMessages;
    private final KafkaProducer<String, Object> kafka;
    private String topic;
    private final String ddlTopic;
    private final MaxwellKafkaPartitioner partitioner;
    private final MaxwellKafkaPartitioner ddlPartitioner;
    private final KeyFormat keyFormat;

    public ConfluentAvroProducer(MaxwellContext context, Properties kafkaProperties, String kafkaTopic) {
        super(context);

        this.topic = kafkaTopic;
        if ( this.topic == null ) {
            this.topic = "maxwell";
        }

        this.kafka = new KafkaProducer<>(kafkaProperties);

        String hash = context.getConfig().kafkaPartitionHash;
        String partitionKey = context.getConfig().kafkaPartitionKey;
        String partitionColumns = context.getConfig().kafkaPartitionColumns;
        String partitionFallback = context.getConfig().kafkaPartitionFallback;
        this.partitioner = new MaxwellKafkaPartitioner(hash, partitionKey, partitionColumns, partitionFallback);
        this.ddlPartitioner = new MaxwellKafkaPartitioner(hash, "database", null,"database");
        this.ddlTopic =  context.getConfig().ddlKafkaTopic;

        if ( context.getConfig().kafkaKeyFormat.equals("hash") )
            keyFormat = KeyFormat.HASH;
        else
            keyFormat = KeyFormat.ARRAY;

        this.inflightMessages = new InflightMessageList();
    }

    private Integer getNumPartitions(String topic) {
        try {
            return this.kafka.partitionsFor(topic).size(); //returns 1 for new topics
        } catch (KafkaException e) {
            LOGGER.error("Topic '" + topic + "' name does not exist. Exception: " + e.getLocalizedMessage());
            throw e;
        }
    }

    private String generateTopic(String topic, RowMap r){
        return topic.replaceAll("%\\{database\\}", r.getDatabase()).replaceAll("%\\{table\\}", r.getTable());
    }

    @Override
    public void push(RowMap r) throws Exception {
        String key = r.pkToJson(keyFormat);
        String value = r.toJSON(outputConfig);

        if ( value == null ) { // heartbeat row or other row with suppressed output
            inflightMessages.addMessage(r.getPosition());
            BinlogPosition newPosition = inflightMessages.completeMessage(r.getPosition());

            if ( newPosition != null ) {
                context.setPosition(newPosition);
            }

            return;
        }

        KafkaCallback callback;
        ProducerRecord<String, Object> record;
        if (r instanceof DDLMap) {

            record = new ProducerRecord<>(this.ddlTopic, this.ddlPartitioner.kafkaPartition(r, getNumPartitions(this.ddlTopic)), key, (Object) value);

            callback = new KafkaCallback(inflightMessages, r.getPosition(), r.isTXCommit(), this.context, key, value);
        } else {
            MaxwellEnvelope maxwellEnvelope = new MaxwellEnvelope(
                    r.getDatabase(),
                    r.getTable(),
                    r.getRowType(),
                    r.getTimestamp(),
                    r.getXid(),
                    r.isTXCommit(),
                    r.getPosition().toString(),
                    r.getServerId(),
                    r.getThreadId(),
                    r.getData(outputConfig.includesNulls),
                    r.getOldData(outputConfig.includesNulls));

            String topic = generateTopic(this.topic, r);
            record = new ProducerRecord<>(topic, this.partitioner.kafkaPartition(r, getNumPartitions(topic)), key, (Object) maxwellEnvelope);

            callback = new KafkaCallback(inflightMessages, r.getPosition(), r.isTXCommit(), this.context, key, maxwellEnvelope);
        }

        if ( r.isTXCommit() )
            inflightMessages.addMessage(r.getPosition());


		/* if debug logging isn't enabled, release the reference to `value`, which can ease memory pressure somewhat */
        if ( !KafkaCallback.LOGGER.isDebugEnabled() )
            value = null;

        kafka.send(record, callback);
    }

    @Override
    public void writePosition(BinlogPosition p) throws SQLException {
        // ensure that we don't prematurely advance the binlog pointer.
        inflightMessages.addMessage(p);
        inflightMessages.completeMessage(p);
    }
}
