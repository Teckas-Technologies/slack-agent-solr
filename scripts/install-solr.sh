#!/bin/bash

# Install Apache Solr

SOLR_VERSION="9.7.0"
SOLR_DIR="/opt/solr"

echo "Installing Apache Solr ${SOLR_VERSION}..."

# Check if Java is installed
if ! command -v java &> /dev/null; then
    echo "Java is not installed. Installing OpenJDK 17..."
    sudo apt update
    sudo apt install -y openjdk-17-jdk
fi

# Download Solr
cd /tmp

# Try multiple mirror options
MIRRORS=(
    "https://archive.apache.org/dist/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz"
    "https://dlcdn.apache.org/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz"
)

DOWNLOADED=false
for MIRROR in "${MIRRORS[@]}"; do
    echo "Trying: ${MIRROR}"
    if wget -q --spider "${MIRROR}"; then
        wget "${MIRROR}" -O "solr-${SOLR_VERSION}.tgz"
        DOWNLOADED=true
        break
    fi
done

if [ "$DOWNLOADED" = false ]; then
    echo "❌ Could not download Solr. Trying latest from archive..."
    # Get latest available version from archive
    LATEST=$(curl -s https://archive.apache.org/dist/solr/solr/ | grep -oP 'href="\K[0-9]+\.[0-9]+\.[0-9]+' | sort -V | tail -1)
    if [ -n "$LATEST" ]; then
        SOLR_VERSION=$LATEST
        echo "Using version: ${SOLR_VERSION}"
        wget "https://archive.apache.org/dist/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz" -O "solr-${SOLR_VERSION}.tgz"
    else
        echo "❌ Failed to find any Solr version"
        exit 1
    fi
fi

# Extract
echo "Extracting Solr..."
sudo tar -xzf "solr-${SOLR_VERSION}.tgz" -C /opt

# Create symlink
sudo rm -f ${SOLR_DIR}
sudo ln -s "/opt/solr-${SOLR_VERSION}" ${SOLR_DIR}

# Set permissions
sudo chown -R $USER:$USER "/opt/solr-${SOLR_VERSION}"

# Verify installation
if [ -f "${SOLR_DIR}/bin/solr" ]; then
    echo ""
    echo "✅ Solr ${SOLR_VERSION} installed successfully at ${SOLR_DIR}"
    echo ""
    echo "To start Solr:"
    echo "  ${SOLR_DIR}/bin/solr start"
    echo ""
    echo "To create the documents core:"
    echo "  ${SOLR_DIR}/bin/solr create -c documents"
    echo ""
    echo "Solr Admin UI: http://localhost:8983/solr"
else
    echo "❌ Installation failed"
    exit 1
fi
