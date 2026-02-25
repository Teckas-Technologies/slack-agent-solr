# InfoBot - Slack Document Agent with Apache Solr

A RAG-based Slack bot that searches Google Drive documents using Apache Solr and generates answers with Gemini AI.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  SPRING BOOT APPLICATION                     │
├─────────────────────────────────────────────────────────────┤
│  Slack Controller → Query Engine → Gemini AI → Response     │
│         ↓                ↓                                   │
│    Slack SDK        Apache Solr                             │
│                    (Port 8983)                              │
└─────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Java 17+
- Maven 3.6+
- Apache Solr 9.4

## Quick Start

### 1. Install Apache Solr

```bash
./scripts/install-solr.sh
```

### 2. Start Solr

```bash
./scripts/start-solr.sh
```

### 3. Configure Environment

```bash
cp .env.example .env
# Edit .env with your credentials
```

### 4. Build and Run

```bash
./start.sh
```

## Configuration

Edit `.env` file:

| Variable | Description |
|----------|-------------|
| `SLACK_BOT_TOKEN` | Slack bot token (xoxb-...) |
| `SLACK_SIGNING_SECRET` | Slack signing secret |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | Google service account JSON |
| `GOOGLE_DELEGATED_USER` | Email for domain-wide delegation |
| `GOOGLE_DRIVE_FOLDER_IDS` | Comma-separated folder IDs |
| `GEMINI_API_KEY` | Google Gemini API key |

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Health check |
| `POST /slack/events` | Slack events webhook |

## Solr Admin

Access Solr Admin UI at: http://localhost:8983/solr

## Project Structure

```
slack-agent-solr/
├── pom.xml
├── src/main/java/com/infobot/
│   ├── InfoBotApplication.java
│   ├── config/
│   │   └── SolrConfig.java
│   ├── controller/
│   │   ├── SlackController.java
│   │   └── HealthController.java
│   ├── service/
│   │   ├── SolrSearchService.java
│   │   ├── DocumentProcessorService.java
│   │   ├── GoogleDriveService.java
│   │   ├── GeminiService.java
│   │   ├── QueryEngineService.java
│   │   └── DocumentSyncService.java
│   └── model/
│       ├── Document.java
│       ├── SearchResult.java
│       └── DriveDocument.java
├── src/main/resources/
│   └── application.yml
├── solr/documents/conf/
│   ├── managed-schema
│   └── solrconfig.xml
└── scripts/
    ├── install-solr.sh
    └── start-solr.sh
```

## License

MIT
