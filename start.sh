#!/bin/bash

# Start InfoBot - Slack Agent with Apache Solr
# Works on Linux and macOS

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "   InfoBot - Slack Agent with Apache Solr"
echo "=========================================="

# Detect OS
OS="unknown"
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS="linux"
    echo "Platform: Linux"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    OS="mac"
    echo "Platform: macOS"
else
    echo "Platform: $OSTYPE"
fi

# Solr configuration
SOLR_HOME=${SOLR_HOME:-/opt/solr}
COLLECTION_NAME="documents"

# Check if Solr is installed
if [ ! -f "$SOLR_HOME/bin/solr" ]; then
    # Try alternate paths
    if [ -f "/opt/solr-9.7.0/bin/solr" ]; then
        SOLR_HOME="/opt/solr-9.7.0"
    elif [ "$OS" == "mac" ] && command -v solr &> /dev/null; then
        # Homebrew installation
        SOLR_HOME=$(dirname $(dirname $(which solr)))
    else
        echo ""
        echo "Solr not found. Please install Solr first:"
        echo "  sudo ./scripts/install-solr.sh"
        echo ""
        exit 1
    fi
fi

echo "Solr Home: $SOLR_HOME"

# Function to check if Solr is running
is_solr_running() {
    curl -s "http://localhost:8983/solr/admin/ping" > /dev/null 2>&1
}

# Start Solr if not running
if ! is_solr_running; then
    echo "Starting Solr..."
    $SOLR_HOME/bin/solr start

    # Wait for Solr to start
    echo "Waiting for Solr to start..."
    for i in {1..30}; do
        if is_solr_running; then
            echo "Solr started!"
            break
        fi
        sleep 1
    done

    if ! is_solr_running; then
        echo "Failed to start Solr. Please check the logs."
        exit 1
    fi
else
    echo "Solr is already running"
fi

# Check if collection exists
collection_exists() {
    curl -s "http://localhost:8983/solr/$COLLECTION_NAME/admin/ping" > /dev/null 2>&1
}

# Create collection if it doesn't exist
if ! collection_exists; then
    echo "Creating Solr collection '$COLLECTION_NAME'..."
    $SOLR_HOME/bin/solr create -c $COLLECTION_NAME
    sleep 2
fi

# Copy schema to Solr
if [ -f "$PROJECT_DIR/solr/documents/conf/managed-schema" ]; then
    CONF_DIR="$SOLR_HOME/server/solr/$COLLECTION_NAME/conf"
    if [ -d "$CONF_DIR" ]; then
        echo "Syncing Solr schema..."
        cp "$PROJECT_DIR/solr/documents/conf/managed-schema" "$CONF_DIR/"
    fi
fi

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo ""
    echo "Maven not found. Please install Maven:"
    if [ "$OS" == "mac" ]; then
        echo "  brew install maven"
    else
        echo "  sudo apt install maven"
    fi
    echo ""
    exit 1
fi

# Build the application
echo "Building application..."
mvn clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo "Build successful"
echo ""

# Run the application with environment from application.yml
echo "Starting InfoBot..."

# Check for .env file and source individual variables
if [ -f .env ]; then
    echo "Loading environment from .env..."

    # Export simple variables (not JSON)
    while IFS='=' read -r key value || [ -n "$key" ]; do
        # Skip comments and empty lines
        [[ $key =~ ^#.*$ ]] && continue
        [[ -z "$key" ]] && continue

        # Trim whitespace from key
        key=$(echo "$key" | xargs)

        # Skip if key is empty after trim
        [[ -z "$key" ]] && continue

        # Skip JSON values (start with {)
        if [[ "$value" == "{"* ]]; then
            continue
        fi

        # Export the variable
        export "$key=$value"
    done < .env
fi

# Start the application
java -Xms1g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -jar target/slack-agent-solr-1.0.0.jar
