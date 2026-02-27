#!/bin/bash

# Start Apache Solr and create documents collection if needed
# Works on Linux and macOS

SOLR_DIR="/opt/solr"
COLLECTION_NAME="documents"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo "   Starting Apache Solr"
echo "=========================================="

# Detect OS
OS="unknown"
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS="linux"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    OS="mac"
fi

# Check if Solr is installed
if [ ! -f "${SOLR_DIR}/bin/solr" ]; then
    echo "Solr not found at ${SOLR_DIR}"
    echo ""
    echo "Please install Solr first:"
    echo "  ./scripts/install-solr.sh"
    exit 1
fi

# Function to check if Solr is running
is_solr_running() {
    if curl -s "http://localhost:8983/solr/admin/ping" > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Start Solr if not running
if ! is_solr_running; then
    echo "Starting Solr..."
    ${SOLR_DIR}/bin/solr start

    # Wait for Solr to start
    echo "Waiting for Solr to start..."
    for i in {1..30}; do
        if is_solr_running; then
            echo "Solr started successfully!"
            break
        fi
        sleep 1
    done

    if ! is_solr_running; then
        echo "Failed to start Solr"
        exit 1
    fi
else
    echo "Solr is already running"
fi

# Check if collection exists
collection_exists() {
    curl -s "http://localhost:8983/solr/admin/collections?action=LIST" | grep -q "\"${COLLECTION_NAME}\""
}

# Create collection if it doesn't exist
if ! collection_exists; then
    echo "Creating Solr collection: ${COLLECTION_NAME}..."
    ${SOLR_DIR}/bin/solr create -c ${COLLECTION_NAME}

    # Wait for collection to be created
    sleep 2
fi

# Copy schema files if they exist
if [ -f "${PROJECT_DIR}/solr/documents/conf/managed-schema" ]; then
    echo "Syncing Solr schema..."

    # Find the collection config directory
    CONF_DIR="${SOLR_DIR}/server/solr/${COLLECTION_NAME}/conf"

    if [ -d "$CONF_DIR" ]; then
        cp "${PROJECT_DIR}/solr/documents/conf/managed-schema" "${CONF_DIR}/"

        if [ -f "${PROJECT_DIR}/solr/documents/conf/solrconfig.xml" ]; then
            cp "${PROJECT_DIR}/solr/documents/conf/solrconfig.xml" "${CONF_DIR}/"
        fi

        # Reload collection to apply schema changes
        echo "Reloading collection..."
        curl -s "http://localhost:8983/solr/admin/cores?action=RELOAD&core=${COLLECTION_NAME}" > /dev/null
    fi
fi

echo ""
echo "=========================================="
echo "   Solr is running!"
echo "=========================================="
echo ""
echo "Admin UI: http://localhost:8983/solr"
echo "Collection: ${COLLECTION_NAME}"
echo ""
