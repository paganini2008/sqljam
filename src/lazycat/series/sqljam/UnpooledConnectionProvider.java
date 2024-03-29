package lazycat.series.sqljam;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * UnpooledConnectionProvider
 * 
 * @author Fred Feng
 * @version 1.0
 */
public class UnpooledConnectionProvider extends AbstractConnectionProvider {

	private String driverClassName;
	private String username;
	private String password;
	private String url;

	public UnpooledConnectionProvider(String driverClassName, String url, String username, String password) {
		setDriverClassName(driverClassName);
		setUsername(username);
		setPassword(password);
		setUrl(url);
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public void setDriverClassName(String driverClassName) {
		try {
			Class.forName(driverClassName);
		} catch (ClassNotFoundException e) {
			throw new ConnectionException("Can't find the driverClassName: " + driverClassName, e);
		}
		this.driverClassName = driverClassName;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public String getUrl() {
		return url;
	}

	public String getDriverClassName() {
		return driverClassName;
	}

	public Connection openConnection() {
		try {
			return DriverManager.getConnection(url, username, password);
		} catch (SQLException e) {
			throw new ConnectionException(e);
		}
	}

}
