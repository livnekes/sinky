import { S3Client, ListObjectsV2Command, GetObjectCommand } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";

const s3Client = new S3Client({ region: process.env.AWS_REGION || "us-east-1" });
const BUCKET_NAME = process.env.BUCKET_NAME;
const URL_EXPIRATION = 3600; // 1 hour in seconds

export const handler = async (event) => {
    try {
        // Parse input
        const body = JSON.parse(event.body || "{}");
        const { user_email, prefix } = body;

        // Validate input
        if (!user_email) {
            return {
                statusCode: 400,
                headers: {
                    "Content-Type": "application/json",
                    "Access-Control-Allow-Origin": "*"
                },
                body: JSON.stringify({
                    error: "user_email is required"
                })
            };
        }

        if (!BUCKET_NAME) {
            return {
                statusCode: 500,
                headers: {
                    "Content-Type": "application/json",
                    "Access-Control-Allow-Origin": "*"
                },
                body: JSON.stringify({
                    error: "BUCKET_NAME environment variable not set"
                })
            };
        }

        // Build S3 prefix
        // If prefix is provided, use it to filter further (e.g., "2024-12" for specific month)
        // If no prefix, list all files for the user_email pattern
        let s3Prefix = "";
        if (prefix) {
            // Search for folders starting with user_email and containing the prefix
            s3Prefix = prefix;
        }

        console.log(`Listing objects with prefix: ${s3Prefix}`);

        // List objects in S3
        const listCommand = new ListObjectsV2Command({
            Bucket: BUCKET_NAME,
            Prefix: s3Prefix,
            MaxKeys: 1000 // Can be adjusted or paginated
        });

        const listResponse = await s3Client.send(listCommand);

        if (!listResponse.Contents || listResponse.Contents.length === 0) {
            return {
                statusCode: 200,
                headers: {
                    "Content-Type": "application/json",
                    "Access-Control-Allow-Origin": "*"
                },
                body: JSON.stringify({
                    message: "No files found",
                    files: []
                })
            };
        }

        // Filter files by user_email and generate download URLs
        const files = [];
        for (const object of listResponse.Contents) {
            const key = object.Key;

            // Check if the file belongs to the user_email
            // Format is: email_guid/YYYY-MM/timestamp.jpg
            if (key.startsWith(user_email)) {
                // Generate pre-signed URL for download
                const getObjectCommand = new GetObjectCommand({
                    Bucket: BUCKET_NAME,
                    Key: key
                });

                const downloadUrl = await getSignedUrl(s3Client, getObjectCommand, {
                    expiresIn: URL_EXPIRATION
                });

                files.push({
                    key: key,
                    size: object.Size,
                    lastModified: object.LastModified,
                    downloadUrl: downloadUrl
                });
            }
        }

        return {
            statusCode: 200,
            headers: {
                "Content-Type": "application/json",
                "Access-Control-Allow-Origin": "*"
            },
            body: JSON.stringify({
                message: `Found ${files.length} files`,
                files: files,
                urlExpiresIn: `${URL_EXPIRATION} seconds`
            })
        };

    } catch (error) {
        console.error("Error:", error);
        return {
            statusCode: 500,
            headers: {
                "Content-Type": "application/json",
                "Access-Control-Allow-Origin": "*"
            },
            body: JSON.stringify({
                error: "Internal server error",
                message: error.message
            })
        };
    }
};
