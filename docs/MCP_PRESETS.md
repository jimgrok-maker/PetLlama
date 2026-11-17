# MCP presets (Fetch, Time, Filesystem)

PetLlama can add three built-in MCP servers from the MCP screen. Each **Add** saves a server that is **turned off** until you flip its switch. The suggested URLs point at 4650ge (`192.168.1.250`). You can change a URL before you tap Add. GitHub and SearXNG are not touched.

| Preset | URL | What it does |
| --- | --- | --- |
| Fetch | `http://192.168.1.250:8089/mcp` | Reads a web page for the model |
| Time | `http://192.168.1.250:8090/mcp` | Current time, clock set to America/New_York |
| Filesystem | `http://192.168.1.250:8091/mcp` | Reads one folder on 4650ge |

The phone only speaks Streamable HTTP. The official Fetch, Time, and Filesystem servers speak stdio, so the compose file runs each one behind [supergateway](https://github.com/supercorp-ai/supergateway). That gateway serves `/mcp` in stateless mode and turns CORS on, the same idea as the SearXNG MCP server already on this machine.

## Start them on 4650ge

1. Copy `docs/mcp-presets-compose.yml` onto 4650ge.
2. For Filesystem, open the file and set the host folder. The placeholder is `/srv/petllama/files`. The volume ends in `:ro`, and the server is started with `--read-only`, so the model can read that folder and cannot change it.
3. To allow writes later, remove `:ro` from the volume and remove `--read-only` from the Filesystem command, then start the stack again.
4. Create the folder if it does not exist yet (`mkdir -p /srv/petllama/files`).
5. From the folder that contains the compose file:

```bash
docker compose -f mcp-presets-compose.yml up -d
```

6. A quick check from the server, or from the phone's browser: `http://192.168.1.250:8089/healthz` (and ports 8090 and 8091) should say `ok`.

The first start downloads the server packages, so 4650ge needs a network path out. After that, `restart: unless-stopped` brings them back when the machine reboots.

## Turn one on in the app

1. Open **MCP servers**.
2. On the preset card, leave the URL or edit it, then tap **Add**.
3. The new row appears with the switch off. Tap **Test**.
4. When Test shows a tool count, flip the switch. The wrench in chat still has to be on for the model to call tools.

The **+** button is unchanged. Use it for GitHub, SearXNG, or any other Streamable HTTP server.
