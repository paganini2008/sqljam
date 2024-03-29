package lazycat.series.sqljam.relational;

/**
 * SequenceDefinition
 * 
 * @author Fred Feng
 * @version 1.0
 */
public interface SequenceDefinition {

	String getName();

	int getStartWith();

	int getIncrementBy();

	int getMaxValue();

	int getMinValue();

	int getCache();

	SchemaDefinition getSchemaDefinition();

}