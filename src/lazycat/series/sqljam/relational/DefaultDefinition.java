package lazycat.series.sqljam.relational;

import lazycat.series.sqljam.field.DataType;

/**
 * DefaultDefinition
 * 
 * @author Fred Feng
 * @version 1.0
 */
public interface DefaultDefinition {

	ColumnDefinition getColumnDefinition();

	String getValue();

	DataType getDataType();

}
