package lazycat.series.sqljam;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import lazycat.series.converter.TypeConverter;
import lazycat.series.jdbc.JdbcType;
import lazycat.series.jdbc.TypeHandlerRegistry;
import lazycat.series.sqljam.transcation.Transaction;

/**
 * SessionExecutor
 * 
 * @author Fred Feng
 * @version 1.0
 */
public interface SessionExecutor {

	List<Map<String, Object>> list(Transaction transaction, Executable query);

	List<Map<String, Object>> list(Transaction transaction, String sql, Object[] parameters, JdbcType[] jdbcTypes);

	<T> List<T> list(Transaction transaction, Executable query, Class<T> objectClass);

	<T> List<T> list(Transaction transaction, String sql, Object[] parameters, JdbcType[] jdbcTypes, Class<T> objectClass);

	<T> Iterator<T> iterator(Transaction transaction, Executable query, Class<T> objectClass);

	<T> Iterator<T> iterator(Transaction transaction, String sql, Object[] parameters, JdbcType[] jdbcTypes, Class<T> objectClass);

	Iterator<Map<String, Object>> iterator(Transaction transaction, Executable query);

	Iterator<Map<String, Object>> iterator(Transaction transaction, String sql, Object[] parameters, JdbcType[] jdbcTypes);

	<T> T first(Transaction transaction, Executable query, final Class<T> objectClass);

	<T> T first(Transaction transaction, String sql, Object[] parameters, JdbcType[] jdbcTypes, final Class<T> objectClass);

	<T> T getResult(Transaction transaction, Executable query, final Class<T> requiredType);

	<T> T getResult(Transaction transaction, String sql, Object[] parameters, JdbcType[] jdbcTypes, final Class<T> requiredType);

	KeyStore keyStore(Class<?> mappedClass);

	int batch(Transaction transaction, Executable executable);

	int batch(Transaction transaction, String sql, List<Object[]> parameterList, JdbcType[] jdbcTypes);

	int update(Transaction transaction, Executable executable);

	int update(Transaction transaction, String sql, Object[] parameters, JdbcType[] jdbcTypes);
	
	int update(Transaction transaction, Executable executable, KeyStore keyStore);

	void setTypeConverter(TypeConverter typeConverter);

	void setTypeHandlerRegistry(TypeHandlerRegistry typeHandlerRegistry);

	Configuration getConfiguration();
}