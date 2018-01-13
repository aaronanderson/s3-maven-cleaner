A Java Based AWS Lambda to purge prior snapshot deployments from an S3 repository.


The Clojar Maven S3 plugin can be used to deploy maven snapshots to a central S3 directory.  

```XML
	<pluginRepositories>
		<pluginRepository>
			<id>clojars.org</id>
			<name>Clojars Repository</name>
			<url>http://clojars.org/repo</url>
		</pluginRepository>
	</pluginRepositories>
	
	<repositories>
		<repository>
			<id>release-repository</id>
			<name>AWS S3 Maven Release</name>
			<url>s3p://some-maven-repository/releases</url>
			<snapshots>
				<enabled>false</enabled>
			</snapshots>
		</repository>
		<repository>
			<id>snapshot-repository</id>
			<name>AWS S3 Maven Snapshot</name>
			<url>s3p://some-maven-repository/snapshots</url>
			<snapshots>
				<enabled>true</enabled>
			</snapshots>
		</repository>
	</repositories>

	<distributionManagement>
		<repository>
			<id>release-repository</id>
			<name>Repository Name</name>
			<url>s3p://some-maven-repository/releases</url>
		</repository>
		<snapshotRepository>
			<id>snapshot-repository</id>
			<name>Repository Name</name>
			<url>s3p://some-maven-repository/snapshots</url>
		</snapshotRepository>
	</distributionManagement>
	
	<build>
	    ....
		<extensions>
			<extension>
				<groupId>s3-wagon-private</groupId>
				<artifactId>s3-wagon-private</artifactId>
				<version>1.3.0</version>
			</extension>
		</extensions>
	</build>
```	
	
	
Deploying snapshots to a shared Cloud storage location for small in-house projects saves the time and expense of maintaining a central maven artifact repository. However overtime stale snapshots can accumulate and increase storage costs. This simple AWS lambda iterates through maven metadata files, identifies the latest version, and then deletes the old snapshots from an S3 bucket. Since the maven metadata files are checksumed this utility does not modify the index files and only deletes maven artifacts.  
	
AWS CLI commands to register and schedule the lambda:
```
aws events put-rule --name s3-maven-cleaner-scheduled-rule --schedule-expression "rate(7 days)"
aws iam create-role --role-name s3-maven-cleaner-lambda-role --assume-role-policy-document file://s3-maven-cleaner-lambda-create-role.json
aws iam put-role-policy --role-name s3-maven-cleaner-lambda-role --policy-name LambdaServiceRolePolicy --policy-document file://s3-maven-cleaner-lambda-put-role-policy.json
aws lambda create-function --function-name S3MavenCleaner --runtime java8 --role arn:aws:iam::999999999999:role/s3-maven-cleaner-lambda-role --handler s3mavencleaner.S3MavenCleanerHandler --timeout 60 --memory-size 256 --environment Variables={maven_snapshots_s3_bucket=some-maven-repository,maven_snapshots_s3_region=us-east-1} --zip-file fileb://target/s3-maven-cleaner-20171001-SNAPSHOT.jar
aws lambda update-function-code --function-name S3MavenCleaner --zip-file fileb://target/s3-maven-cleaner-20171001-SNAPSHOT.jar
aws lambda add-permission --function-name S3MavenCleaner --statement-id s3-maven-cleaner-scheduled-scheduled-event --action "lambda:InvokeFunction" --principal events.amazonaws.com --source-arn arn:aws:events:us-west-1:999999999999:rule/s3-maven-cleaner-scheduled-rule
aws events put-targets --rule s3-maven-cleaner-scheduled-rule --targets file://s3-maven-cleaner-scheduled-rule-targets.json
```