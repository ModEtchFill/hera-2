package com.paypal.hera.jdbc;

import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.*;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.paypal.hera.client.HeraClientImpl;
import com.paypal.hera.conf.HeraClientConfigHolder;
import com.paypal.hera.ex.HeraClientException;
import com.paypal.hera.jdbc.HeraConnection;
import com.paypal.hera.util.MurmurHash3;
import com.paypal.hera.util.HeraJdbcConverter;
import com.paypal.hera.util.HeraJdbcUtil;

/** Helper functions for testing. */
public class Util {
	
	/** Returns a new HeraConnection.  Connects to server in 
	System.getProperty("SERVER_URL") and defaults to localhost:11111 */
	public static HeraConnection makeDbConn() {
		try {
			String host = System.getProperty("SERVER_URL", "1:127.0.0.1:11111"); 
			HeraClientConfigHolder.clear();
			Properties props = new Properties();
			props.setProperty(HeraClientConfigHolder.RESPONSE_TIMEOUT_MS_PROPERTY, "3000");
			props.setProperty(HeraClientConfigHolder.SUPPORT_RS_METADATA_PROPERTY, "true");
			props.setProperty(HeraClientConfigHolder.SUPPORT_COLUMN_INFO_PROPERTY, "true");
			props.setProperty(HeraClientConfigHolder.ENABLE_SHARDING_PROPERTY, "true");
			Class.forName("com.paypal.hera.jdbc.HeraDriver");
			return (HeraConnection)DriverManager.getConnection("jdbc:hera:" + host, props);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}


	public static void runDml(Connection dbConn, String sql) {
		try {
	                PreparedStatement pst = dbConn.prepareStatement(sql);
        	        pst.executeUpdate();
                	dbConn.commit();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/** Compiles and starts Hera server and a docker Mysql. Cleans up 
	old Hera and Mysql before it remakes new ones. Uses GOROOT to find 
	go compiler and GOPATH to find the binaries and make a directory
	with config and logs for the test hera server. */
	public static void makeAndStartHeraMux(HashMap<String,String> cfg) {
		try {
			makeAndStartHeraMuxInternal(cfg);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	static void makeAndStartHeraMuxInternal(HashMap<String,String> cfg) throws IOException, InterruptedException {
		if (cfg == null) {
			cfg = new HashMap<String,String>();
		}
		Runtime.getRuntime().exec("go install github.com/paypal/hera/mux github.com/paypal/hera/worker/mysqlworker").waitFor();

		// TODO skip cleanup to allow use of server from prior test
		String dockerName = "mysql55";
		Runtime.getRuntime().exec("docker stop "+dockerName).waitFor();
		Runtime.getRuntime().exec("docker rm "+dockerName).waitFor();
		Runtime.getRuntime().exec("killall -ILL mux mysqlworker").waitFor();
		// TODO better cleanup of old mux
		
		Runtime.getRuntime().exec("docker run --name "+dockerName+" -e MYSQL_ROOT_PASSWORD=1-testDb -e MYSQL_DATABASE=heratestdb -d mysql:latest").waitFor();

        	// find its IP
		ProcessBuilder pbIp = new ProcessBuilder(
				"docker",
				"inspect",
				"--format",
				"{{ .NetworkSettings.IPAddress }}",
				dockerName);
		Process p = pbIp.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
		p.waitFor();
		String ip= reader.readLine();
		// now try tcp to ip 3306 for mysql to come up
		boolean didConn = false;
		for (int i = 0; i < 111; i++) {
			Thread.sleep(1222);
			Socket clientSocket = new Socket();
			try {
				clientSocket.connect(new InetSocketAddress(ip, 3306), 2000);
			} catch (ConnectException e) {
				continue;
			} catch (SocketTimeoutException e) {
				continue;
			}
			clientSocket.close();
			didConn = true;
			break;
		}
		if (!didConn) {
			throw new RuntimeException("mysql docker did not come up");
		}
		
		String gopath = System.getenv().get("GOPATH");
		String basedir = gopath+"/srv/";
		File basedirF = new File(basedir);
		basedirF.mkdir();


		File symLinkTarget;
		symLinkTarget = new File(basedir+"mux");
		if (!symLinkTarget.exists()) {
			Files.createSymbolicLink(
				symLinkTarget.toPath(),
				(new File(gopath+"/bin/" + symLinkTarget.getName())).toPath());
		}
		symLinkTarget = new File(basedir+"mysqlworker");
		if (!symLinkTarget.exists()) {
			Files.createSymbolicLink(
				symLinkTarget.toPath(),
				(new File(gopath+"/bin/" + symLinkTarget.getName())).toPath());
		}

		BufferedWriter writer;
		writer = new BufferedWriter(new FileWriter(basedir+"cal_client.txt"));
		writer.write("enable_cal=true\n");
		writer.write("cal_handler=file\n");
		writer.write("cal_pool_name=stage_hera\n");
		writer.write("cal_log_file=./cal.log\n");
		writer.write("cal_pool_stack_enable=true\n");
		writer.close();

		cfg.putIfAbsent("bind_ip", "0.0.0.0");
		cfg.putIfAbsent("bind_port", "11111");
		cfg.putIfAbsent("opscfg.hera-test.server.max_connections","2");
		cfg.putIfAbsent("log_level","5");
		cfg.putIfAbsent("rac_sql_interval","0");
		cfg.putIfAbsent("database_type","mysql");
		// cert_chain_file=srvChain.crt
		// key_file=srv2.key
		writer = new BufferedWriter(new FileWriter(basedir+"hera.txt"));
		for (String key : cfg.keySet()) {
			writer.write(key + "=" + cfg.get(key) + "\n");
		}
		writer.close();


		ProcessBuilder pb = new ProcessBuilder("./mux", "--name", "hera-test");
		pb.directory(basedirF);
		Map<String,String> env = pb.environment();
		env.put("TWO_TASK","tcp("+ip+":3306)/heratestdb");
		env.put("username","root");
		env.put("password","1-testDb");
		s_hera = pb.start();

		didConn = false;
		for (int i = 0; i < 111; i++) {
			Thread.sleep(1222);
			Socket clientSocket = new Socket();
			try {
				clientSocket.connect(new InetSocketAddress("localhost", 11111), 2000);
			} catch (ConnectException e) {
				continue;
			} catch (SocketTimeoutException e) {
				continue;
			}
			clientSocket.close();
			didConn = true;
			break;
		}
		if (!didConn) {
			throw new RuntimeException("hera srv did not come up");
		}
	}
	static Process s_hera;
	
}
