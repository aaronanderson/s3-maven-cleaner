package s3mavencleaner;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import software.amazon.awssdk.core.regions.Region;
import software.amazon.awssdk.core.sync.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request.Builder;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

public class S3MavenCleaner {

	static final Logger LOG = LogManager.getFormatterLogger(S3MavenCleaner.class);

	public static final String MAVEN_BUCKET = "some-maven-repository";

	private final S3Client s3Client;
	private final String mavenSnapshotS3Bucket;

	public S3MavenCleaner(String mavenSnapshotS3Bucket, Region region) throws Exception {
		this.mavenSnapshotS3Bucket = mavenSnapshotS3Bucket;
		this.s3Client = S3Client.builder().region(region).build();
	}

	public static void main(String... args) {
		try {
			S3MavenCleaner cleaner = new S3MavenCleaner(MAVEN_BUCKET, Region.US_EAST_1);
			cleaner.clean();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	public void clean() throws Exception {
		// first pass - discover all maven-metadata.xml files
		Map<String, S3MavenArtifact> snapshots = new HashMap<>();
		identifySnapshots(snapshots);

		// analyze available metadata, determine latest version, derive whitelist of files to retain
		for (S3MavenArtifact s3Artifact : snapshots.values()) {
			Set<String> whitelistedPrefixes = new HashSet<>();
			Set<String> blacklistedSuffixes = new HashSet<>();
			latestVersionFiles(s3Artifact, whitelistedPrefixes, blacklistedSuffixes);
			// second pass - delete all non-whitelisted files
			// System.out.format("%s - Whitelist %s Blacklist: %s\n", s3Artifact.key, whitelistedPrefixes, blacklistedSuffixes);
			purgeFiles(s3Artifact.directory, whitelistedPrefixes, blacklistedSuffixes);
		}

	}

	private void identifySnapshots(Map<String, S3MavenArtifact> snapshotCache) throws IOException, XmlPullParserException {
		ListObjectsV2Response listResponse = null;
		String continuationToken = null;
		int count = 0;
		do {
			Builder builder = ListObjectsV2Request.builder().bucket(mavenSnapshotS3Bucket).prefix("snapshot").continuationToken(continuationToken).maxKeys(500);
			listResponse = s3Client.listObjectsV2(builder.build());
			continuationToken = listResponse.nextContinuationToken();
			if (listResponse.contents() != null) {
				for (S3Object obj : listResponse.contents()) {
					// System.out.println("Retrieved: " + obj);
					if (obj.key().endsWith("maven-metadata.xml")) {
						String directory = obj.key().substring(0, obj.key().lastIndexOf('/'));
						ResponseInputStream<GetObjectResponse> metadataIn = s3Client.getObject(GetObjectRequest.builder().bucket(MAVEN_BUCKET).key(obj.key()).build());
						MetadataXpp3Reader reader = new MetadataXpp3Reader();
						Metadata metadata = reader.read(metadataIn);
						String key = mavenKey(metadata);
						S3MavenArtifact snapshot = snapshotCache.computeIfAbsent(key, k -> new S3MavenArtifact(k));

						if (metadata.getVersion() == null) {// a directory
							snapshot.directory = directory;
							snapshot.directoryMetadata = metadata;
							// Snapshot snapshot = metadata.getVersioning().getSnapshot();
							// String filter = String.format("%s-%s", snapshot.getTimestamp(), snapshot.getBuildNumber());
						} else {
							snapshot.versionMetadata.add(metadata);
						}
					}
				}
			}
			count += listResponse.contents().size();
			LOG.info("Checked %d files", count);

		} while (listResponse.isTruncated());

	}

	private String mavenKey(Metadata metadata) {
		return metadata.getGroupId() + ":" + metadata.getArtifactId();
	}

	private void latestVersionFiles(S3MavenArtifact s3Artifact, Set<String> whitelistedPrefixes, Set<String> blacklistedSuffixes) throws IOException {
		if (s3Artifact.directoryMetadata == null || s3Artifact.versionMetadata.isEmpty()) {
			throw new IOException(String.format("Metadata is incomplete for %s, cannot proceed", s3Artifact.key));
		}

		// automatically add the directory maven-metadata.xml
		whitelistedPrefixes.add(String.format("%s/maven-metadata.xml", s3Artifact.directory));
		// find the last updated version
		Metadata lastUpdatedMetadata = s3Artifact.versionMetadata.get(0);
		for (Metadata metadata : s3Artifact.versionMetadata) {
			String latestTimestamp = lastUpdatedMetadata.getVersioning().getSnapshot().getTimestamp();
			String currentTimestamp = metadata.getVersioning().getSnapshot().getTimestamp();
			if (currentTimestamp.compareTo(latestTimestamp) > 0) {
				lastUpdatedMetadata = metadata;
			}
		}
		for (Metadata metadata : s3Artifact.versionMetadata) {
			if (metadata != lastUpdatedMetadata) {
				blacklistedSuffixes.add(metadata.getVersion() + "/");
			}
		}
		whitelistedPrefixes.add(String.format("%s/%s/maven-metadata.xml", s3Artifact.directory, lastUpdatedMetadata.getVersion()));
		for (SnapshotVersion snapshotFile : lastUpdatedMetadata.getVersioning().getSnapshotVersions()) {
			if (snapshotFile.getUpdated().equals(lastUpdatedMetadata.getVersioning().getLastUpdated())) {
				whitelistedPrefixes.add(String.format("%s/%s/%s-%s", s3Artifact.directory, lastUpdatedMetadata.getVersion(), lastUpdatedMetadata.getArtifactId(), snapshotFile.getVersion()));
			}
		}
	}

	private void purgeFiles(String directory, Set<String> whitelistedPrefixes, Set<String> blacklistedSuffixes) {
		ListObjectsV2Response listResponse = null;
		String continuationToken = null;
		do {
			Builder builder = ListObjectsV2Request.builder().bucket(MAVEN_BUCKET).prefix(directory).continuationToken(continuationToken).maxKeys(500);
			List<ObjectIdentifier> deleteItems = new LinkedList<>();
			listResponse = s3Client.listObjectsV2(builder.build());
			continuationToken = listResponse.nextContinuationToken();
			if (listResponse.contents() != null) {
				for (S3Object obj : listResponse.contents()) {
					boolean whitelisted = false;
					if (obj.key().endsWith("/")) {
						whitelisted = true;
						for (String suffix : blacklistedSuffixes) {
							if (obj.key().endsWith(suffix)) {
								whitelisted = false;
								break;
							}
						}
					} else {
						for (String prefix : whitelistedPrefixes) {
							if (obj.key().startsWith(prefix)) {
								whitelisted = true;
								break;
							}
						}
					}
					// LOG.info("Status: %s Key: %s", whitelisted, obj.key());
					if (!whitelisted) {
						deleteItems.add(ObjectIdentifier.builder().key(obj.key()).build());

					}

				}
			}
			if (!deleteItems.isEmpty()) {
				DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder().bucket(MAVEN_BUCKET).delete(Delete.builder().objects(deleteItems).build()).build();
				DeleteObjectsResponse deleteResponse = s3Client.deleteObjects(deleteRequest);
				for (DeletedObject deleted : deleteResponse.deleted()) {
					LOG.info("Deleted %s", deleted.key());
				}
			}

			// System.out.format("Deleted %s\n", deleteResponse.deleted());

		} while (listResponse.isTruncated());

	}

	private static class S3MavenArtifact {
		final String key;
		String directory;
		Metadata directoryMetadata;
		List<Metadata> versionMetadata = new LinkedList<>();

		private S3MavenArtifact(String key) {
			this.key = key;
		}

	}

}
