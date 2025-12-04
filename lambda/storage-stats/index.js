import { S3Client, ListObjectsV2Command } from "@aws-sdk/client-s3";

const s3Client = new S3Client({ region: "eu-central-1" });
const BUCKET_NAME = "photos-sinky";

export const handler = async (event) => {
    try {
        console.log("Received event:", JSON.stringify(event, null, 2));

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
