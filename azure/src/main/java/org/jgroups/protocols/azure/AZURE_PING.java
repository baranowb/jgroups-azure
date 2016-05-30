/*
 * Copyright 2015 Red Hat Inc., and individual contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jgroups.protocols.azure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jgroups.Address;
import org.jgroups.annotations.Property;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.protocols.FILE_PING;
import org.jgroups.protocols.PingData;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentials;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

/**
 * Implementation of PING protocols for AZURE using Storage Blobs. See
 * /DESIGN.md for design.
 *
 * @author Radoslav Husar
 * @author baranowb
 * @version Jun 2015
 */
public class AZURE_PING extends FILE_PING {

	private static final Log log = LogFactory.getLog(AZURE_PING.class);

	@Property(description = "The name of the storage account.")
	protected String storage_account_name;

	@Property(description = "The secret account access key.", exposeAsManagedAttribute = false)
	protected String storage_access_key;

	@Property(description = "Container to store ping information in. Must be valid DNS name.")
	protected String container;

	@Property(description = "Whether or not to use HTTPS to connect to Azure.")
	protected boolean use_https = true;

	public static final int STREAM_BUFFER_SIZE = 4096;

	private CloudBlobContainer containerReference;

	static {
		ClassConfigurator.addProtocol((short) 530, AZURE_PING.class);
	}

	
	@Override
	public void init() throws Exception {
		super.init();

		// Validate configuration
		// Can throw IAEs
		this.validateConfiguration();

		try {
			final StorageCredentials credentials = new StorageCredentialsAccountAndKey(storage_account_name,
					storage_access_key);
			final CloudStorageAccount storageAccount = new CloudStorageAccount(credentials, use_https);
			final CloudBlobClient blobClient = storageAccount.createCloudBlobClient();
			this.containerReference = blobClient.getContainerReference(container);
			final boolean created = containerReference.createIfNotExists();

			if (created) {
				if (log.isInfoEnabled())
					log.info("Created container named '" + container + "'.");
			} else {
				if (log.isDebugEnabled())
					log.debug("Using existing container named '" + container + "'.");
			}

		} catch (Exception ex) {
			log.error("Error creating a storage client! Check your configuration.", ex);
		}
	}

	public void validateConfiguration() throws IllegalArgumentException {
		// Validate that container name is configured and must be all lowercase
		if (this.container == null || !this.container.toLowerCase().equals(this.container) || this.container.contains("--")
				|| this.container.startsWith("-") || this.container.length() < 3 || this.container.length() > 63) {
			throw new IllegalArgumentException(
					"Container name must be configured and must meet Azure requirements (must be a valid DNS name).");
		}
		// Account name and access key must be both configured for write access
		if (this.storage_account_name == null || this.storage_access_key == null) {
			throw new IllegalArgumentException("Account name and key must be configured.");
		}
		// Lets inform users here that https would be preferred
		if (!this.use_https) {
			log.info("Configuration is using HTTP, consider switching to HTTPS instead.");
		}

	}

	@Override
	protected void createRootDir() {
		// Do not remove this!
		// There is no root directory to create, overriding here with noop.
	}

	@Override
	protected void remove(final String clustername, final Address addr) {
		if (clustername == null || addr == null) {
			return;
		}

		final String filename = addressToFilename(clustername, addr);

		try {
			final CloudBlockBlob blob = this.containerReference.getBlockBlobReference(filename);
			final boolean deleted = blob.deleteIfExists();

			if (deleted) {
				if (log.isDebugEnabled())
					log.debug("Tried to delete file '" + filename + "' but it was already deleted.");
			} else {
				if (log.isTraceEnabled())
					log.trace("Deleted file '" + filename + "'.");
			}

		} catch (Exception ex) {
			log.error("Error deleting files.", ex);
		}
	}

	@Override
	protected synchronized List<PingData> readAll(final String clustername) {
		if (clustername == null) {
			return new ArrayList<PingData>();
		}
		final String prefix = sanitize(clustername);
		final Iterable<ListBlobItem> listBlobItems = this.containerReference.listBlobs(prefix);
		final List<PingData> retval = new ArrayList<PingData>();
		for (ListBlobItem blobItem : listBlobItems) {
			try {
				// If the item is a blob and not a virtual directory.
				// n.b. what an ugly API this is
				if (blobItem instanceof CloudBlob) {
					final CloudBlob blob = (CloudBlob) blobItem;
					final ByteArrayOutputStream os = new ByteArrayOutputStream(STREAM_BUFFER_SIZE);
					blob.download(os);
					final byte[] pingBytes = os.toByteArray();
					final PingData pd = parsePingData(pingBytes);
					if (pd == null) {
						((CloudBlob) blobItem).deleteIfExists();
					} else {
						retval.add(pd);
					}
				}
			} catch (Exception ex) {
				log.error("Error fetching ping data.",ex);
			}
		}
		return retval;
	}

	@Override
	protected synchronized void writeToFile(final PingData data,final String clustername) {
		final ByteArrayOutputStream bos = new ByteArrayOutputStream(STREAM_BUFFER_SIZE);
		final DataOutputStream dos = new DataOutputStream(bos);
		try {
			data.writeTo(dos);
			String filename = addressToFilename(clustername, local_addr);

			byte[] rawData = bos.toByteArray();

			// Upload the file
			CloudBlockBlob blob = this.containerReference.getBlockBlobReference(filename);
			blob.upload(new ByteArrayInputStream(rawData), rawData.length);

		} catch (Exception ex) {
			log.error("Error marshalling and uploading ping data.", ex);
		}
	}

	protected PingData parsePingData(final byte[] raw) {
		final PingData data = new PingData();
		final DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw));
		try {
			data.readFrom(dis);
			return data;
		} catch (Exception e) {
			log.error("Failed to deserialize member data", e);
		} finally {
			try {
				dis.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * Sanitizes names replacing backslashes and forward slashes with a dash.
	 */
	protected static String sanitize(final String name) {
		return name.replace('/', '-').replace('\\', '-');
	}

	protected static String addressToFilename(final String clustername, final Address address) {
		return sanitize(clustername) + "-" + addressAsString(address);
	}

}
