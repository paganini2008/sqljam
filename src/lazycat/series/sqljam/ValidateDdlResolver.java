package lazycat.series.sqljam;

import lazycat.series.sqljam.relational.TableDefinition;

/**
 * ValidateDdlResolver
 * 
 * @author Fred Feng
 * @version 1.0
 */
public class ValidateDdlResolver implements DdlResolver {

	public void resolve(JdbcAdmin jdbcAdmin, Class<?> mappedClass) {
		if (!jdbcAdmin.tableExists(mappedClass)) {
			TableDefinition tableDefinition = jdbcAdmin.getConfiguration().getTableDefinition(mappedClass);
			throw new DdlResolverException(tableDefinition.getTableName() + " is not existed.");
		}
		jdbcAdmin.validateTable(mappedClass);
	}

}
