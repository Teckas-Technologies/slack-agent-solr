#!/bin/bash

# Install Apache Solr - Works on Linux and macOS

SOLR_VERSION="9.7.0"
SOLR_DIR="/opt/solr"

echo "=========================================="
echo "   Apache Solr ${SOLR_VERSION} Installer"
echo "=========================================="

# Detect OS
OS="unknown"
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    OS="linux"
    echo "Detected: Linux"
elif [[ "$OSTYPE" == "darwin"* ]]; then
    OS="mac"
    echo "Detected: macOS"
else
    echo "Unsupported OS: $OSTYPE"
    exit 1
fi

# Function to install Java
install_java() {
    echo "Java is not installed. Installing OpenJDK 17..."

    if [ "$OS" == "linux" ]; then
        sudo apt update
        sudo apt install -y openjdk-17-jdk
    elif [ "$OS" == "mac" ]; then
        # Check if Homebrew is installed
        if ! command -v brew &> /dev/null; then
            echo "Installing Homebrew..."
            /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

            # Add Homebrew to PATH for Apple Silicon
            if [[ $(uname -m) == "arm64" ]]; then
                echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zshrc
                eval "$(/opt/homebrew/bin/brew shellenv)"
            fi
        fi

        brew install openjdk@17

        # Create symlink for system Java wrappers
        sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk 2>/dev/null || true

        echo ""
        echo "Add Java to PATH by running:"
        echo '  echo '\''export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"'\'' >> ~/.zshrc'
        echo '  source ~/.zshrc'
    fi
}

# Check if Java is installed
if ! command -v java &> /dev/null; then
    install_java
else
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
    echo "Java found: version $JAVA_VERSION"
    if [ "$JAVA_VERSION" -lt 11 ]; then
        echo "Java 11+ required. Installing Java 17..."
        install_java
    fi
fi

# Download Solr
cd /tmp

# Try multiple mirror options
MIRRORS=(
    "https://archive.apache.org/dist/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz"
    "https://dlcdn.apache.org/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz"
)

# Use curl on macOS (wget may not be installed)
download_file() {
    local url=$1
    local output=$2

    if command -v wget &> /dev/null; then
        wget -q "$url" -O "$output"
    elif command -v curl &> /dev/null; then
        curl -sL "$url" -o "$output"
    else
        echo "Neither wget nor curl found. Installing curl..."
        if [ "$OS" == "mac" ]; then
            brew install curl
        else
            sudo apt install -y curl
        fi
        curl -sL "$url" -o "$output"
    fi
}

check_url() {
    local url=$1
    if command -v wget &> /dev/null; then
        wget -q --spider "$url"
    else
        curl -sI "$url" | head -n 1 | grep -q "200"
    fi
}

DOWNLOADED=false
for MIRROR in "${MIRRORS[@]}"; do
    echo "Trying: ${MIRROR}"
    if check_url "${MIRROR}"; then
        download_file "${MIRROR}" "solr-${SOLR_VERSION}.tgz"
        DOWNLOADED=true
        break
    fi
done

if [ "$DOWNLOADED" = false ]; then
    echo "Could not download Solr ${SOLR_VERSION}. Trying to find latest version..."
    LATEST=$(curl -s https://archive.apache.org/dist/solr/solr/ | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -V | tail -1)
    if [ -n "$LATEST" ]; then
        SOLR_VERSION=$LATEST
        echo "Using version: ${SOLR_VERSION}"
        download_file "https://archive.apache.org/dist/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz" "solr-${SOLR_VERSION}.tgz"
    else
        echo "Failed to find any Solr version"
        exit 1
    fi
fi

# Extract
echo "Extracting Solr..."
sudo mkdir -p /opt
sudo tar -xzf "solr-${SOLR_VERSION}.tgz" -C /opt

# Create symlink
sudo rm -f ${SOLR_DIR}
sudo ln -s "/opt/solr-${SOLR_VERSION}" ${SOLR_DIR}

# Set permissions
if [ "$OS" == "mac" ]; then
    sudo chown -R $(whoami):staff "/opt/solr-${SOLR_VERSION}"
else
    sudo chown -R $USER:$USER "/opt/solr-${SOLR_VERSION}"
fi

# Clean up
rm -f "/tmp/solr-${SOLR_VERSION}.tgz"

# Verify installation
if [ -f "${SOLR_DIR}/bin/solr" ]; then
    echo ""
    echo "=========================================="
    echo "   Solr ${SOLR_VERSION} installed successfully!"
    echo "=========================================="
    echo ""
    echo "Location: ${SOLR_DIR}"
    echo ""
    echo "To start Solr:"
    echo "  ${SOLR_DIR}/bin/solr start"
    echo ""
    echo "To create the documents collection:"
    echo "  ${SOLR_DIR}/bin/solr create -c documents"
    echo ""
    echo "Solr Admin UI: http://localhost:8983/solr"
else
    echo "Installation failed"
    exit 1
fi
