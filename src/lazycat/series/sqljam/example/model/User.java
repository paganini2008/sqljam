package lazycat.series.sqljam.example.model;

import lazycat.series.sqljam.AutoDdl;
import lazycat.series.sqljam.annotation.Column;
import lazycat.series.sqljam.annotation.PrimaryKey;
import lazycat.series.sqljam.annotation.Table;

@Table(name = "tb_user_old", autoDdl = AutoDdl.UPDATE)
public class User {

	@PrimaryKey
	@Column(autoIncrement = true)
	private int id;

	@Column
	private String username;

	@Column
	private String password;

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String toString() {
		return "User [id=" + id + ", username=" + username + ", password=" + password + "]";
	}

}
