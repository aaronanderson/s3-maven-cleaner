package s3mavencleaner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.regions.Region;

public class S3MavenCleanerHandler implements RequestHandler<Void, Void> {

	static final Logger LOG = LogManager.getFormatterLogger(S3MavenCleanerHandler.class);

	public static final String MAVEN_S3_BUCKET_ENV = "maven_snapshots_s3_bucket";
	public static final String MAVEN_S3_REGION_ENV = "maven_snapshots_s3_region";

	private final String mavenSnapshotS3Bucket;
	private final Region region;

	public S3MavenCleanerHandler() {
		mavenSnapshotS3Bucket = System.getenv().get(MAVEN_S3_BUCKET_ENV);
		region = Region.of(System.getenv().get(MAVEN_S3_REGION_ENV));
	}

	@Override
	public Void handleRequest(Void input, Context context) {
		try {
			LOG.info("Clean Request %s %s", mavenSnapshotS3Bucket, region);
			S3MavenCleaner cleaner = new S3MavenCleaner(mavenSnapshotS3Bucket, region);
			cleaner.clean();
		} catch (Throwable t) {
			t.printStackTrace();
			LOG.error(t);
		}
		return null;
	}

}
