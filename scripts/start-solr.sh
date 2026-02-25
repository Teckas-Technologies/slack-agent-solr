#!/bin/bash

# Start Apache Solr and create documents core if needed

SOLR_DIR="/opt/solr"
CORE_NAME="documents"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "Starting Apache Solr..."

# Start Solr if not running
if ! pgrep -f "solr" > /dev/null; then
    ${SOLR_DIR}/bin/solr start
    sleep 5
fi

# Check if core exists
if ! curl -s "http://localhost:8983/solr/admin/cores?action=STATUS&core=${CORE_NAME}" | grep -q "\"${CORE_NAME}\""; then
    echo "Creating Solr core: ${CORE_NAME}..."
    ${SOLR_DIR}/bin/solr create -c ${CORE_NAME}

    # Copy schema files
    echo "Copying schema configuration..."
    cp "${PROJECT_DIR}/solr/documents/conf/managed-schema" "${SOLR_DIR}/server/solr/${CORE_NAME}/conf/"
    cp "${PROJECT_DIR}/solr/documents/conf/solrconfig.xml" "${SOLR_DIR}/server/solr/${CORE_NAME}/conf/"

    # Reload core
    curl "http://localhost:8983/solr/admin/cores?action=RELOAD&core=${CORE_NAME}"
fi

echo ""
echo "✅ Solr is running!"
echo "   Admin UI: http://localhost:8983/solr"
echo "   Core: ${CORE_NAME}"
