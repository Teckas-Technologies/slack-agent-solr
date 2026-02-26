#!/bin/bash

# Start InfoBot - Slack Agent with Apache Solr

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "   InfoBot - Slack Agent with Apache Solr"
echo "=========================================="

SOLR_HOME=${SOLR_HOME:-/opt/solr-9.7.0}
COLLECTION_NAME="documents"

# Start Solr if not running
if ! curl -s "http://localhost:8983/solr/admin/ping" > /dev/null 2>&1; then
    echo "Starting Solr..."
    $SOLR_HOME/bin/solr start
    sleep 5
fi

# Create collection if it doesn't exist
if ! curl -s "http://localhost:8983/solr/$COLLECTION_NAME/admin/ping" > /dev/null 2>&1; then
    echo "Creating Solr collection '$COLLECTION_NAME'..."
    $SOLR_HOME/bin/solr create -c $COLLECTION_NAME
fi

# Copy schema to Solr
if [ -f "$PROJECT_DIR/solr/documents/conf/managed-schema" ]; then
    echo "Syncing Solr schema..."
    cp "$PROJECT_DIR/solr/documents/conf/managed-schema" "$SOLR_HOME/server/solr/$COLLECTION_NAME/conf/"
fi

# Build the application
echo "Building application..."
mvn clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "❌ Build failed!"
    exit 1
fi

echo "✅ Build successful"
echo ""

# Run the application with environment from application.yml
# Environment variables should be set in the shell or passed via -D flags
echo "Starting InfoBot..."

# Check for .env file and source individual variables
if [ -f .env ]; then
    echo "Loading environment from .env..."

    # Export simple variables (not JSON)
    while IFS='=' read -r key value; do
        # Skip comments and empty lines
        [[ $key =~ ^#.*$ ]] && continue
        [[ -z "$key" ]] && continue

        # Skip JSON values (start with {)
        if [[ "$value" == "{"* ]]; then
            continue
        fi

        # Export the variable
        export "$key=$value"
    done < .env
fi

java -Xms1g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -jar target/slack-agent-solr-1.0.0.jar
