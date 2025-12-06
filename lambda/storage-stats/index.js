import { S3Client, ListObjectsV2Command } from "@aws-sdk/client-s3";

const s3Client = new S3Client({ region: "eu-central-1" });
const BUCKET_NAME = "photos-sinky";

/**
 * Extract Cognito Identity ID from prefix
 * Supports two formats:
 * - email@example.com (legacy direct email - no Cognito ID)
 * - email@example.com_cognitoIdentityId (email with Cognito Identity ID)
 *
 * Returns the Cognito Identity ID part after the underscore, or null if not found
 */
function extractCognitoIdFromPrefix(prefix) {
    if (prefix.includes('_')) {
        const parts = prefix.split('_');
        // Cognito Identity ID is after the underscore (format: region:uuid)
        return parts[1] || null;
    }
    return null;
}


export const handler = async (event) => {
    try {
        console.log("Received event:", JSON.stringify(event, null, 2));

        // Verify IAM authentication from API Gateway
        const userArn = event.requestContext?.identity?.userArn;

        if (!userArn) {
            console.error("No IAM authorization context found");
            console.log("requestContext:", JSON.stringify(event.requestContext, null, 2));
            return {
                statusCode: 401,
                headers: {
                    'Content-Type': 'application/json',
                    'Access-Control-Allow-Origin': '*'
                },
                body: JSON.stringify({
                    error: 'Unauthorized - No IAM authorization'
                })
            };
        }

        console.log(`Request from IAM role: ${userArn}`);

        // Verify the request is from the PhotoUploaderCognitoRole
        if (!userArn.includes('PhotoUploaderCognitoRole')) {
            console.error(`Unauthorized role: ${userArn}`);
            return {
                statusCode: 403,
                headers: {
                    'Content-Type': 'application/json',
                    'Access-Control-Allow-Origin': '*'
                },
                body: JSON.stringify({
                    error: 'Forbidden - Invalid IAM role'
                })
            };
        }

        // Extract Cognito Identity ID from API Gateway
        const requestCognitoIdentityId = event.requestContext.identity.cognitoIdentityId;
        console.log(`✓ Cognito Identity ID: ${requestCognitoIdentityId}`);

        // Parse request body
        let body;
        if (typeof event.body === 'string') {
            body = JSON.parse(event.body);
        } else {
            body = event.body || event;
        }

        const prefix = body.prefix;

        if (!prefix) {
            return {
                statusCode: 400,
                headers: {
                    'Content-Type': 'application/json',
                    'Access-Control-Allow-Origin': '*'
                },
                body: JSON.stringify({
                    error: 'prefix is required'
                })
            };
        }

        // Extract Cognito Identity ID from the prefix
        const prefixCognitoId = extractCognitoIdFromPrefix(prefix);
        console.log(`Cognito ID from prefix: ${prefixCognitoId}`);

        // Verify that the Cognito Identity ID in the prefix matches the authenticated user
        if (requestCognitoIdentityId && prefixCognitoId) {
            if (prefixCognitoId !== requestCognitoIdentityId) {
                console.error(`Cognito Identity ID mismatch: prefix=${prefixCognitoId}, request=${requestCognitoIdentityId}`);
                return {
                    statusCode: 403,
                    headers: {
                        'Content-Type': 'application/json',
                        'Access-Control-Allow-Origin': '*'
                    },
                    body: JSON.stringify({
                        error: 'Forbidden - You can only access your own statistics'
                    })
                };
            }
            console.log(`✓ Cognito Identity verification passed: ${requestCognitoIdentityId}`);
        } else {
            console.warn("Could not verify Cognito Identity ID - falling back to IAM role authorization only");
        }

        console.log(`Listing objects with prefix: ${prefix}`);

        let objectCount = 0;
        let totalSize = 0;
        let continuationToken = undefined;

        // List all objects with pagination
        do {
            const params = {
                Bucket: BUCKET_NAME,
                Prefix: prefix,
                MaxKeys: 1000
            };

            if (continuationToken) {
                params.ContinuationToken = continuationToken;
            }

            const command = new ListObjectsV2Command(params);
            const response = await s3Client.send(command);

            if (response.Contents) {
                for (const obj of response.Contents) {
                    objectCount++;
                    totalSize += obj.Size;
                }
            }

            continuationToken = response.NextContinuationToken;

        } while (continuationToken);

        console.log(`Found ${objectCount} objects with total size ${totalSize} bytes`);

        return {
            statusCode: 200,
            headers: {
                'Content-Type': 'application/json',
                'Access-Control-Allow-Origin': '*'
            },
            body: JSON.stringify({
                objectCount: objectCount,
                totalSize: totalSize
            })
        };

    } catch (error) {
        console.error("Error:", error);
        return {
            statusCode: 500,
            headers: {
                'Content-Type': 'application/json',
                'Access-Control-Allow-Origin': '*'
            },
            body: JSON.stringify({
                error: 'Internal server error',
                message: error.message
            })
        };
    }
};
