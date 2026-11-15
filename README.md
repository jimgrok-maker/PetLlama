# PetLlama

An Android app for chatting with a [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server` on your home network (or over Tailscale).

The phone talks to the server's OpenAI-compatible API:

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions` (streamed)

Plain `http://` is allowed on purpose. A server on the LAN usually does not have HTTPS, and the app still connects.

## Install the app on your phone

You do not need Android Studio. The APK is built by GitHub Actions.

1. These steps work after this project is on the `main` branch.
2. On GitHub, open the **Actions** tab, choose **Build debug APK**, then **Run workflow** → **Run workflow**.
   - Or, from a computer: `git tag v1.0.0` and `git push origin v1.0.0`.
3. When the run is green, open **Releases** on the repo (the phone browser is fine). Download **PetLlama-debug.apk**.
4. Open the file on the phone. If Android blocks it, allow installs from the browser or from My Files, then tap Install.

A normal push to `main` also builds the APK and stores it on that workflow run as an artifact named `PetLlama-debug` (a zip, easier to grab from a computer). It does **not** create a Release. A Release — the easy phone download — is published only when you run the workflow by hand, or when you push a version tag such as `v1.0.0`.

The debug APK is signed with the automatic debug key. That is enough to install it on your own phone. Android 8 or newer is required.

## Use it

1. Open PetLlama and tap **+**.
2. Name the server and set the URL, for example `http://192.168.1.50:8080`. Use the computer's LAN address, not `127.0.0.1` (that means the phone itself).
3. If you started `llama-server` with an API key, paste the same key. Leave it blank if you didn't.
4. Tap **Test**. A good result looks like `1 model(s) loaded`.
5. Tap **Open chat** and send a message. The reply streams in. The stop button cuts it off and keeps whatever was already written.
6. The history button lists chats for **this** server. Tap a row to open that chat again. The ✕ deletes that chat and its messages.

Temperature, top-p, max tokens, and the system prompt apply to the chat you are in right now. They are not saved separately on each old conversation.

The wrench in the chat bar turns MCP tools on or off for the chat you have open. It starts on.

## MCP tools

PetLlama can hand the model tools from MCP servers on your LAN, the same idea as the llama.cpp web UI. The phone speaks MCP **Streamable HTTP**, protocol **2025-06-18**.

`llama-server` only understands tool calls if you start it with `--jinja`. Example:

```bash
llama-server -m your-model.gguf --host 0.0.0.0 --port 8080 --jinja
```

`--host 0.0.0.0` makes the server listen on the LAN. `127.0.0.1` is the phone itself, so that address will not work in the app. The same rule applies to MCP servers: bind them to the LAN address (or `0.0.0.0`), not localhost.

1. Open **MCP servers** from the puzzle-piece icon on the server list or in chat.
2. Tap **+**. Name it, and paste the full URL, for example `http://192.168.1.50:3000/mcp`.
3. If the server wants a token, paste it in **Bearer token**. It is sent as `Authorization: Bearer …` on every MCP request. Extra headers go one per line, like `X-Api-Key: abc`.
4. Tap **Test**. A good result looks like `12 tool(s) from searx`. The switch on the card turns that server on or off. Only enabled servers are offered to the model.
5. In chat, leave the wrench highlighted and send a message. When the model calls a tool, a short row appears with the server, the tool, the arguments, and the result. Tap the row to expand it. Stop still cuts the reply off.

A GitHub MCP server often has a few dozen tools and wants a bearer token. SearXNG is usually one search tool and no token. You type the URLs yourself; the app does not guess them.

If a server is down, or a tool returns an error, that text is given back to the model so it can still answer. The chat does not crash. Test shows the real error (HTTP status and message), not a bare "failed".

A long reply keeps a small notification up ("Waiting for the model to finish"). Allow notifications if Android asks. That stops the phone from cutting the connection when you switch apps for a moment. Very large tool results are shortened before they are sent back, so a big GitHub reply does not stall the model.

Lots of tools use a lot of the model's context. Turn off servers you are not using.

## Build on a computer

You need JDK 17 (or 21) and Android SDK platform 34. From this folder:

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`.

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

`./gradlew testDebugUnitTest` runs the client checks against a tiny fake llama-server (health, model list, streaming chat, API key) and a fake MCP server (initialize, tool list, tool call, and the tool-call loop).
