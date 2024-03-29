package lazycat.series.sqljam.query;

import lazycat.series.sqljam.Configuration;
import lazycat.series.sqljam.ParameterCollector;

/**
 * Minus Clause
 * 
 * @author Fred Feng
 * @version 1.0
 */
public class Minus extends AbstractQuery {

	public Minus(From left, From right) {
		this.left = left;
		this.right = right;
	}

	private final From left;
	private final From right;

	public String getText(Configuration configuration) {
		return configuration.getJdbcAdmin().getFeature().minus(left.getText(configuration), right.getText(configuration));
	}

	public void setParameters(ParameterCollector parameterCollector, Configuration configuration) {
		left.setParameters(parameterCollector, configuration);
		right.setParameters(parameterCollector, configuration);
	}

	public Class<?> findMappedClass(String tableAlias, Configuration configuration) {
		Class<?> type = left.findMappedClass(tableAlias, configuration);
		return type != null ? type : right.findMappedClass(tableAlias, configuration);
	}

	public int size() {
		int size = left.size();
		size += right.size();
		return size;
	}
}
