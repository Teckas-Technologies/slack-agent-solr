# InfoBot - Slack Agent with Apache Solr

A RAG-based (Retrieval-Augmented Generation) Slack bot that indexes documents from **Google Drive** and **Confluence**, stores them in **Apache Solr**, and answers questions using **Google Gemini AI**.

Ask questions in Slack — InfoBot finds the most relevant documents and generates accurate answers with source references.

---

## Architecture

```
┌──────────────┐       ┌──────────────────┐       ┌──────────────┐
│  Google Drive │──────▶│                  │◀──────│  Confluence  │
│  (Documents)  │       │  Document Sync   │       │   (Pages)    │
└──────────────┘       │    Service       │       └──────────────┘
                        │  (Every 2 hrs)   │
                        └────────┬─────────┘
                                 │
                          Extract & Chunk
                                 │
                                 ▼
                        ┌──────────────────┐
                        │   Apache Solr    │
                        │  (Full-Text      │
                        │   Search Index)  │
                        └────────┬─────────┘
                                 │
                              Search
                                 │
┌──────────────┐       ┌────────┴─────────┐       ┌──────────────┐
│    Slack     │──────▶│  Query Engine    │──────▶│  Gemini AI   │
│  (User asks  │       │   Service        │       │  (Generate   │
│   question)  │◀──────│                  │◀──────│   Answer)    │
└──────────────┘       └──────────────────┘       └──────────────┘
```

### How It Works

1. **Document Ingestion** — Syncs documents from Google Drive and Confluence every 2 hours. Extracts text from PDFs, Word, Excel, Google Docs, and more. Splits content into overlapping chunks for better search relevance.

2. **Indexing** — Chunks are indexed in Apache Solr with boosted fields (document name weighted 50x higher than content) for accurate retrieval.

3. **Question Answering** — When a user asks a question in Slack (via @mention or DM), the Query Engine searches Solr using eDisMax, retrieves the top matching documents, and sends them as context to Gemini AI to generate a grounded answer with source references.

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Backend | Java 17, Spring Boot 3.2 |
| Search Engine | Apache Solr 9.7 |
| AI Model | Google Gemini 2.5 Flash |
| Document Sources | Google Drive API v3, Confluence REST API |
| Messaging | Slack Events API |
| Document Parsing | Apache POI, PDFBox, Jsoup |
| Build | Maven |

---

## Supported Document Types

- PDF, Word (.doc, .docx), Excel (.xls, .xlsx)
- Plain text (.txt), CSV (.csv)
- Google Docs, Google Sheets (exported automatically)
- Confluence pages (HTML parsed to text)

---

## Project Structure

```
slack-agent-solr/
├── src/main/java/com/infobot/
│   ├── InfoBotApplication.java          # Entry point
│   ├── config/
│   │   └── SolrConfig.java              # Solr client configuration
│   ├── controller/
│   │   ├── SlackController.java         # Slack event handler
│   │   └── HealthController.java        # Health & stats endpoints
│   ├── model/
│   │   ├── Document.java                # Solr document chunk
│   │   ├── DriveDocument.java           # Source document metadata
│   │   └── SearchResult.java            # Search response
│   └── service/
│       ├── QueryEngineService.java      # Orchestrates search + AI
│       ├── SolrSearchService.java       # Solr indexing & search
│       ├── GeminiService.java           # Gemini AI integration
│       ├── DocumentSyncService.java     # Scheduled sync (2hr)
│       ├── DocumentProcessorService.java# Text extraction & chunking
│       ├── GoogleDriveService.java      # Google Drive API
│       └── ConfluenceService.java       # Confluence API
├── solr/documents/conf/
│   └── managed-schema                   # Solr schema definition
├── scripts/
│   ├── install-solr.sh                  # Solr installation script
│   └── start-solr.sh                    # Start Solr & create collection
├── start.sh                             # Main startup script
├── .env.example                         # Environment template
├── SETUP_GUIDE.md                       # Detailed setup instructions
└── pom.xml                              # Maven dependencies
```

---

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+
- Apache Solr 9.x

### 1. Clone & Install Solr

```bash
git clone https://github.com/Teckas-Technologies/slack-agent-solr.git
cd slack-agent-solr

chmod +x scripts/install-solr.sh
sudo ./scripts/install-solr.sh
```

### 2. Configure Environment

```bash
cp .env.example .env
```

Edit `.env` with your credentials:

```bash
# Slack
SLACK_BOT_TOKEN=xoxb-your-token
SLACK_SIGNING_SECRET=your-secret

# Google Drive
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
GOOGLE_DRIVE_FOLDER_IDS=your-folder-id

# Gemini AI
GEMINI_API_KEY=your-api-key

# Confluence (optional)
CONFLUENCE_BASE_URL=https://company.atlassian.net
CONFLUENCE_USERNAME=your-email
CONFLUENCE_API_TOKEN=your-token
CONFLUENCE_SPACES=PM,DEV
```

### 3. Run

```bash
./start.sh
```

Run in background:

```bash
nohup ./start.sh > app.log 2>&1 &
```

> For detailed setup instructions (Slack bot creation, Google Drive service account, Confluence API tokens), see **[SETUP_GUIDE.md](SETUP_GUIDE.md)**.

---

## Usage

### In Slack Channels (requires @mention)

```
@InfoBot What is the leave policy?
@InfoBot Find documents about onboarding
@InfoBot status
```

### In Direct Messages (no @mention needed)

```
What is the leave policy?
Find documents about onboarding
status
```

### Commands

| Command | Description |
|---------|-------------|
| `status` | Check bot health and document stats |
| `help` | Show available commands |
| Any question | Search documents and generate an answer |

---

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Service health (Solr, Drive, Gemini status) |
| `GET /api/stats` | Document counts by source |
| `GET /api/docs/drive` | List indexed Google Drive documents |
| `GET /api/docs/confluence` | List indexed Confluence pages |
| `POST /slack/events` | Slack event webhook |

---

## Configuration

Key settings in `application.yml`:

| Setting | Default | Description |
|---------|---------|-------------|
| `server.port` | 8000 | Application port |
| `solr.host` | http://localhost:8983/solr | Solr URL |
| `gemini.model` | gemini-2.5-flash | Gemini model |
| `gemini.temperature` | 0.3 | Response creativity (lower = more precise) |
| `document.chunk-size` | 1000 | Characters per chunk |
| `document.chunk-overlap` | 200 | Overlap between chunks |
| `search.max-results` | 20 | Max Solr results per query |
| `sync.interval-minutes` | 2 | Document sync interval |

---

## License

MIT
