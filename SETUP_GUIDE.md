# InfoBot - Setup Guide

A Slack bot that indexes documents from Google Drive and Confluence, answers questions using Gemini AI.

**Platforms:** Linux and macOS

---

## Step 1: Install Solr

```bash
git clone <your-repo-url> slack-agent-solr
cd slack-agent-solr

# Install Solr (auto-detects Linux/macOS)
chmod +x scripts/install-solr.sh
sudo ./scripts/install-solr.sh
```

---

## Step 2: Create Slack Bot

1. Go to https://api.slack.com/apps
2. Click **Create New App** → **From scratch**
3. Name: `InfoBot`, select workspace

### Add Bot Permissions

Go to **OAuth & Permissions** → Add scopes:
- `app_mentions:read`
- `chat:write`
- `channels:history`
- `channels:read`
- `groups:history`
- `im:history`

### Install & Get Tokens

1. Click **Install to Workspace** → **Allow**
2. Copy **Bot User OAuth Token** (starts with `xoxb-`)
3. Go to **Basic Information** → Copy **Signing Secret**

### Enable Events

1. Go to **Event Subscriptions** → Enable
2. Add events: `app_mention`, `message.channels`, `message.groups`, `message.im`
3. Request URL will be added in Step 8

---

## Step 3: Setup Google Drive

1. Go to https://console.cloud.google.com
2. Create new project → Enable **Google Drive API**
3. Go to **IAM & Admin** → **Service Accounts** → Create
4. Go to **Keys** → **Add Key** → **JSON** → Download
5. Save as `service-account.json` in project folder
6. Share your Google Drive folder with the service account email

**Get Folder ID from URL:**
```
https://drive.google.com/drive/folders/1e6N2cV7GTe0ke5oJ2SK8kklWnF-q5j0s
                                       └─────────── Folder ID ───────────┘
```

---

## Step 4: Get Gemini API Key

1. Go to https://aistudio.google.com/apikey
2. Click **Create API Key**
3. Copy the key

---

## Step 5: Setup Confluence (Optional)

1. Go to https://id.atlassian.com/manage-profile/security/api-tokens
2. Click **Create API token** → Copy immediately

**Get Space Key from URL:**
```
https://company.atlassian.net/wiki/spaces/PM/pages/123
                                        └─┘
                                    Space Key = PM
```

---

## Step 6: Configure .env File

```bash
nano .env
```

**Linux:**
```bash
# Slack
SLACK_BOT_TOKEN=xoxb-your-token
SLACK_SIGNING_SECRET=your-secret

# Google Drive
GOOGLE_APPLICATION_CREDENTIALS=/home/username/slack-agent-solr/service-account.json
GOOGLE_DELEGATED_USER=user@company.com
GOOGLE_DRIVE_FOLDER_IDS=your-folder-id

# Gemini
GEMINI_API_KEY=your-api-key

# Confluence (Optional)
CONFLUENCE_BASE_URL=https://company.atlassian.net
CONFLUENCE_USERNAME=your-email
CONFLUENCE_API_TOKEN=your-token
CONFLUENCE_SPACES=PM,DEV
```

**macOS:** Change path to `/Users/username/slack-agent-solr/service-account.json`

---

## Step 7: Run the App

```bash
./start.sh
```

**Run in background:**
```bash
nohup ./start.sh > app.log 2>&1 &
```

---

## Step 8: Connect Slack to App

You need a public URL for Slack to send events.

### Using ngrok (Development)

```bash
ngrok http 8000
```

Copy the HTTPS URL (e.g., `https://abc123.ngrok.io`)

### Configure Slack

1. Go to https://api.slack.com/apps → Select your app
2. **Event Subscriptions** → Request URL:
   ```
   https://your-url/slack/events
   ```
3. Wait for **Verified** checkmark
4. **Save Changes**

---

## Step 9: Test

In Slack:
```
/invite @InfoBot
@InfoBot status
@InfoBot What is the leave policy?
```

---

## Quick Commands

```bash
# Start
./start.sh

# Background
nohup ./start.sh > app.log 2>&1 &

# View logs
tail -f app.log

# Stop
pkill -f slack-agent-solr
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Bot not responding | Check if app is running: `ps aux \| grep slack` |
| Solr not starting | Run: `sudo ./scripts/install-solr.sh` |
| Documents not syncing | Check folder ID and service account permissions |
| Slack verification fails | Ensure URL is publicly accessible |
