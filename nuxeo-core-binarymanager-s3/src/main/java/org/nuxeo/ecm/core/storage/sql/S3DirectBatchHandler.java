/*
 * (C) Copyright 2011-2018 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Luís Duarte
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.storage.sql;

import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.AWS_ID_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.AWS_SECRET_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.BUCKET_NAME_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.BUCKET_PREFIX_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3BinaryManager.BUCKET_REGION_PROPERTY;
import static org.nuxeo.ecm.core.storage.sql.S3Utils.NON_MULTIPART_COPY_MAX_SIZE;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.nuxeo.ecm.automation.server.jaxrs.batch.Batch;
import org.nuxeo.ecm.automation.server.jaxrs.batch.handler.AbstractBatchHandler;
import org.nuxeo.ecm.automation.server.jaxrs.batch.handler.BatchFileInfo;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.binary.BinaryBlob;
import org.nuxeo.ecm.core.blob.binary.LazyBinary;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;

/**
 * Batch Handler allowing direct S3 upload.
 *
 * @since 10.1
 */
public class S3DirectBatchHandler extends AbstractBatchHandler {

    protected static final Pattern REGEX_MULTIPART_ETAG = Pattern.compile("-\\d+$");

    // properties passed at initialization time from extension point

    public static final String ROLE_ARN_PROPERTY = "roleArn";

    public static final String ACCELERATE_MODE_ENABLED_PROPERTY = "accelerateMode";

    protected static final List<String> MANDATORY_PROPERTIES = Arrays.asList( //
            AWS_ID_PROPERTY, //
            AWS_SECRET_PROPERTY, //
            BUCKET_NAME_PROPERTY, //
            BUCKET_REGION_PROPERTY, //
            ROLE_ARN_PROPERTY //
    );

    // keys in the batch properties, returned to the client

    public static final String INFO_AWS_SECRET_KEY_ID = "awsSecretKeyId";

    public static final String INFO_AWS_SECRET_ACCESS_KEY = "awsSecretAccessKey";

    public static final String INFO_AWS_SESSION_TOKEN = "awsSessionToken";

    public static final String INFO_BUCKET = "bucket";

    public static final String INFO_BASE_KEY = "baseKey";

    public static final String INFO_EXPIRATION = "expiration";

    public static final String INFO_AWS_REGION = "region";

    public static final String INFO_USE_S3_ACCELERATE = "useS3Accelerate";

    protected AWSSecurityTokenService stsClient;

    protected AmazonS3 amazonS3;

    protected String region;

    protected String bucket;

    protected String bucketPrefix;

    protected String roleArn;

    protected boolean accelerateModeEnabled;

    @Override
    protected void initialize(Map<String, String> properties) {
        super.initialize(properties);
        for (String property : MANDATORY_PROPERTIES) {
            if (isEmpty(properties.get(property))) {
                throw new NuxeoException("Missing configuration property: " + property);
            }
        }
        region = properties.get(BUCKET_REGION_PROPERTY);
        bucket = properties.get(BUCKET_NAME_PROPERTY);
        bucketPrefix = defaultIfEmpty(properties.get(BUCKET_PREFIX_PROPERTY), "");
        roleArn = properties.get(ROLE_ARN_PROPERTY);
        accelerateModeEnabled = Boolean.parseBoolean(properties.get(ACCELERATE_MODE_ENABLED_PROPERTY));
        String awsSecretKeyId = properties.get(AWS_ID_PROPERTY);
        String awsSecretAccessKey = properties.get(AWS_SECRET_PROPERTY);
        AWSCredentialsProvider awsCredentialsProvider = S3Utils.getAWSCredentialsProvider(awsSecretKeyId,
                awsSecretAccessKey);
        stsClient = AWSSecurityTokenServiceClientBuilder.standard()
                                                        .withRegion(region)
                                                        .withCredentials(awsCredentialsProvider)
                                                        .build();
        amazonS3 = AmazonS3ClientBuilder.standard()
                                        .withRegion(region)
                                        .withCredentials(awsCredentialsProvider)
                                        .withAccelerateModeEnabled(accelerateModeEnabled)
                                        .build();
    }

    @Override
    public Batch getBatch(String batchId) {
        Map<String, Serializable> parameters = getBatchParameters(batchId);
        if (parameters == null) {
            return null;
        }

        // create the batch
        Batch batch = new Batch(batchId, parameters, getName(), getTransientStore());
        AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest().withRoleSessionName(batch.getKey())
                                                                     .withRoleArn(roleArn);
        Credentials credentials = stsClient.assumeRole(assumeRoleRequest).getCredentials();
        Map<String, Object> properties = batch.getProperties();
        properties.put(INFO_AWS_SECRET_KEY_ID, credentials.getAccessKeyId());
        properties.put(INFO_AWS_SECRET_ACCESS_KEY, credentials.getSecretAccessKey());
        properties.put(INFO_AWS_SESSION_TOKEN, credentials.getSessionToken());
        properties.put(INFO_BUCKET, bucket);
        properties.put(INFO_BASE_KEY, bucketPrefix);
        properties.put(INFO_EXPIRATION, credentials.getExpiration().toInstant().toEpochMilli());
        properties.put(INFO_AWS_REGION, region);
        properties.put(INFO_USE_S3_ACCELERATE, accelerateModeEnabled);

        return batch;
    }

    @Override
    public boolean completeUpload(String batchId, String fileIndex, BatchFileInfo fileInfo) {
        String fileKey = fileInfo.getKey();
        ObjectMetadata metadata = amazonS3.getObjectMetadata(bucket, fileKey);
        if (metadata == null) {
            return false;
        }
        String etag = metadata.getETag();
        if (isEmpty(etag)) {
            return false;
        }
        String mimeType = metadata.getContentType();

        ObjectMetadata newMetadata;
        if (metadata.getContentLength() > NON_MULTIPART_COPY_MAX_SIZE) {
            newMetadata = S3Utils.copyFileMultipart(amazonS3, metadata, bucket, fileKey, bucket, etag, true);
        } else {
            newMetadata = S3Utils.copyFile(amazonS3, metadata, bucket, fileKey, bucket, etag, true);
            boolean isMultipartUpload = REGEX_MULTIPART_ETAG.matcher(etag).find();
            if (isMultipartUpload) {
                String previousEtag = etag;
                etag = newMetadata.getETag();
                newMetadata = S3Utils.copyFile(amazonS3, metadata, bucket, previousEtag, bucket, etag, true);
            }
        }

        String blobKey = transientStoreName + ':' + etag;
        String filename = fileInfo.getFilename();
        long length = newMetadata.getContentLength();
        String digest = newMetadata.getContentMD5();
        String blobProviderId = transientStoreName; // TODO decouple this
        Blob blob = new BinaryBlob(new LazyBinary(blobKey, blobProviderId, null), blobKey, filename, mimeType, null,
                digest, length);
        Batch batch = getBatch(batchId);
        batch.addFile(fileIndex, blob, filename, mimeType);

        return true;
    }

}
