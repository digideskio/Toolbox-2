/* This file is part of RD-Switchboard.
 * RD-Switchboard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>. 
 *
 * Author: https://github.com/wizman777
 */

package org.rdswitchboard.importers.browser.s3;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.MediaType;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.io.IOUtils;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.util.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class App {
	private static final String ENCODING = "UTF-8";	
	
	public static void main(String[] args) {
		try {
			if (args.length == 0 || StringUtils.isNullOrEmpty(args[0]))
				throw new Exception("Please provide properties file");
			
            String propertiesFile = args[0];
            Properties properties = new Properties();
	        try (InputStream in = new FileInputStream(propertiesFile)) {
	            properties.load(in);
	        }
	        
	        String source = properties.getProperty("data.source.id");
	        
	        if (StringUtils.isNullOrEmpty(source))
                throw new IllegalArgumentException("Source can not be empty");

	        System.out.println("Source: " + source);
	  
	        String baseUrl = properties.getProperty("base.url");
	        
	        if (StringUtils.isNullOrEmpty(baseUrl))
                throw new IllegalArgumentException("Base URL can not be empty");

	        System.out.println("Base URL: " + baseUrl);

	        String sessionId = properties.getProperty("session.id");
	        
	        if (StringUtils.isNullOrEmpty(sessionId))
                throw new IllegalArgumentException("Session Id can not be empty");

	        System.out.println("Session Id: " + sessionId);
	        
	        String accessKey = properties.getProperty("aws.access.key");
	        String secretKey = properties.getProperty("aws.secret.key");
   
	        String bucket = properties.getProperty("s3.bucket");
	        
	        if (StringUtils.isNullOrEmpty(bucket))
                throw new IllegalArgumentException("AWS S3 Bucket can not be empty");

	        System.out.println("S3 Bucket: " + bucket);
	        
	        String prefix = properties.getProperty("s3.prefix");
	        	
	        if (StringUtils.isNullOrEmpty(prefix))
	            throw new IllegalArgumentException("AWS S3 Prefix can not be empty");
        
	        System.out.println("S3 Prefix: " + prefix);
	    
	        String crosswalk = properties.getProperty("crosswalk");
	        Templates template = null;
	        
	        if (!StringUtils.isNullOrEmpty(crosswalk)) {
	        	System.out.println("Crosswalk: " + crosswalk);
	        	
	        	template = TransformerFactory.newInstance().newTemplates(
	        			new StreamSource(
	        					new FileInputStream(crosswalk)));
	        } 
	        
	        ObjectMapper mapper = new ObjectMapper();
	        
	        Client client = Client.create();
	        Cookie cookie = new Cookie("PHPSESSID", properties.getProperty("session"));
	        
	        AmazonS3 s3client;
	        
	        if (!StringUtils.isNullOrEmpty(accessKey) && !StringUtils.isNullOrEmpty(secretKey)) {
	        	System.out.println("Connecting to AWS via Access and Secret Keys. This is not safe practice, consider to use IAM Role instead.");
		        
	        	AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
		        s3client = new AmazonS3Client(awsCredentials);
	        } else {
	        	System.out.println("Connecting to AWS via Instance Profile Credentials");
	        	
	        	s3client = new AmazonS3Client(new InstanceProfileCredentialsProvider());
	        }	     
	        
	        //String file = "rda/rif/class:collection/54800.xml";
	        
	    	ListObjectsRequest listObjectsRequest;
			ObjectListing objectListing;
			
			String file = prefix + "/latest.txt";
			S3Object object = s3client.getObject(new GetObjectRequest(bucket, file));
			
			String latest;
			try (InputStream txt = object.getObjectContent()) {
				latest = prefix + "/" + IOUtils.toString(txt, StandardCharsets.UTF_8).trim() + "/";
			}
			
			System.out.println("S3 Repository: " + latest);
	        
	        listObjectsRequest = new ListObjectsRequest()
				.withBucketName(bucket)
				.withPrefix(latest);
			do {
				objectListing = s3client.listObjects(listObjectsRequest);
				for (S3ObjectSummary objectSummary : 
					objectListing.getObjectSummaries()) {
					
					file = objectSummary.getKey();
	
					System.out.println("Processing file: " + file);
					
					object = s3client.getObject(new GetObjectRequest(bucket, file));
					String xml = null;
					
					if (null != template) {
						Source reader = new StreamSource(object.getObjectContent());
						StringWriter writer = new StringWriter();
						
						Transformer transformer = template.newTransformer(); 
						transformer.transform(reader, new StreamResult(writer));
						
						xml = writer.toString();
					} else {
						InputStream is = object.getObjectContent();
						
						xml = IOUtils.toString(is, ENCODING);
					}
					
			        URL url = new URL(baseUrl + "/registry/import/import_s3/");

			        StringBuilder sb = new StringBuilder();
			        addParam(sb, "id", source);
			        addParam(sb, "xml", xml);
			        
					//System.out.println(sb.toString());
			        
			        WebResource webResource = client.resource(url.toString());
					ClientResponse response = webResource
							 	.header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:38.0) Gecko/20100101 Firefox/38.0")
							 	.accept( MediaType.APPLICATION_JSON, "*/*" )
							 	.acceptLanguage( "en-US", "en" )
							 	.type( MediaType.APPLICATION_FORM_URLENCODED )
		                        .cookie(cookie)
					 			.post(ClientResponse.class, sb.toString());
					 
					if (response.getStatus() != 200) {
						throw new RuntimeException("Failed : HTTP error code : "
								+ response.getStatus());
					}

					String output = response.getEntity(String.class);
					
					Result result = mapper.readValue(output, Result.class);
					
					if (!result.getStatus().equals("OK")) {
						System.err.println(result.getMessage());
						
						break;
					} else 
						System.out.println(result.getMessage());					
				}
				listObjectsRequest.setMarker(objectListing.getNextMarker());
			} while (objectListing.isTruncated());
	        
		} catch (Exception e) {
            e.printStackTrace();
		}       
	}
	
	public static void addParam(StringBuilder sb, String param, String value) throws UnsupportedEncodingException {
		sb.append("&");
		sb.append(URLEncoder.encode(param, ENCODING));
		sb.append("=");
		sb.append(URLEncoder.encode(value, ENCODING));
	}
}
