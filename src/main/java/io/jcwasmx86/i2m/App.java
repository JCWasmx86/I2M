package io.jcwasmx86.i2m;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.google.gson.GsonBuilder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.Event;
import org.pircbotx.hooks.Listener;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.QuitEvent;

import io.github.ma1uta.matrix.client.StandaloneClient;
import io.github.ma1uta.matrix.event.RoomEvent;
import io.github.ma1uta.matrix.event.message.Text;

public class App {
	@Override
	public String toString() {
		return "App []";
	}

	static class I2MConfiguration {
		public String homeserverHostname;
		public String botName;
		public String botPassword;
		public String ircUserName;
		public String ircHostName;
		public String channelToJoin;
		public String matrixChannelName;
		public String matrixUserName;
	}

	static class AppListener implements Listener, HttpHandler {
		private final StandaloneClient client;
		private final long startTime;
		private final String id;
		private PircBotX bot;
		private final I2MConfiguration config;
		private long bytesSent = 0;
		private long bytesReceived = 0;
		private long messagesReceived = 0;
		private long messagesSent = 0;
		private long noticesSent = 0;

		public AppListener(final StandaloneClient client, final I2MConfiguration config, final HttpServer hs) {
			this.client = client;
			this.config = config;
			this.id = this.client.room().joinByIdOrAlias(config.matrixChannelName, null, null).getRoomId();
			this.startTime = System.currentTimeMillis();
			final var executor = Executors.newFixedThreadPool(1);
			hs.createContext("/", this);
			executor.submit(() -> {
				String batch = null;
				while (true) {
					final var response = this.client.sync().sync(null, batch, true, "online",
							10 * 1000l);
					batch = response.getNextBatch();
					final var rooms = response.getRooms();
					if (rooms == null)
						continue;
					final var join = rooms.getJoin();
					if (join == null)
						continue;
					for (final var room : join.entrySet()) {
						final var joined = room.getValue();
						if (joined.getState() != null
								&& joined.getState().getEvents() != null) {
							joined.getState().getEvents().forEach(this::evalEvent);
						}
						final var timeline = joined.getTimeline();
						if (timeline != null && timeline.getEvents() != null) {
							timeline.getEvents().forEach(this::evalEvent);
						}
					}
					Thread.sleep(1000);
				}
			});
		}

		private void evalEvent(final io.github.ma1uta.matrix.event.Event event) {
			if (event instanceof RoomEvent<?>) {
				final var re = (RoomEvent<?>) event;
				final var content = re.getContent();
				if (content instanceof Text) {
					final var rmc = (Text) content;
					final var body = rmc.getBody();

					if (!re.getSender().equals(this.config.matrixUserName)) {
						return;
					}
					if (re.getOriginServerTs() <= this.startTime) {
						return;
					}
					bytesReceived += body.getBytes().length;
					this.messagesSent++;
					bot.send().message(this.config.channelToJoin, body);
				}
			}
		}

		@Override
		public void onEvent(final Event event) throws Exception {
			if (event instanceof MessageEvent) {
				final var me = (MessageEvent) event;
				final var messageAsString = "[" + me.getUser().getNick() + "] " + me.getMessage();
				bytesSent += messageAsString.getBytes().length;
				this.messagesReceived++;
				this.client.event().sendMessage(this.id, messageAsString);
			} else if (event instanceof JoinEvent) {
				final var je = (JoinEvent) event;
				final var notice = je.getUser().getNick() + " joined!";
				bytesSent += notice.getBytes().length;
				this.noticesSent++;
				this.client.event().sendNotice(this.id, notice);
			} else if (event instanceof QuitEvent) {
				final var qe = (QuitEvent) event;
				final var notice = qe.getUser().getNick() + " left: " + qe.getReason();
				bytesSent += notice.getBytes().length;
				this.noticesSent++;
				this.client.event().sendNotice(this.id, notice);
			}
		}

		public void setBot(PircBotX bot) {
			this.bot = bot;
		}

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				var now = System.currentTimeMillis();
				var uptimeInSeconds = (now - this.startTime) / 1000;
				var times = uptimeInSeconds % (24 * 60 * 60);
				var days = (uptimeInSeconds - times) / (24 * 60 * 60);
				var hours = (times - (times % 3600)) / 3600;
				var secondsLeft = times % 3600;
				var minutes = (secondsLeft - (secondsLeft % 60)) / 60;
				var seconds = (int) (secondsLeft % 60);
				var timestamp = "%d days, %d hours, %d minutes, %d seconds".formatted((int) days, (int) hours, (int) minutes, seconds);
				var html = new StringBuilder();
				html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\" name=\"viewport\" content= \"width=device-width, initial-scale=1.0\"></head>").append("<body>");
				html.append("<b>Uptime: </b>").append(timestamp).append("<br>");
				html.append("<b>Sent data: </b>").append(this.formatData(this.bytesSent)).append("<br>");
				html.append("<b>Received data: </b>").append(this.formatData(this.bytesReceived)).append("<br>");
				html.append("<b>Messages sent: </b>").append(this.messagesSent).append("<br>");
				html.append("<b>Messages received: </b>").append(this.messagesReceived).append("<br>");
				html.append("<b>Notices sent: </b>").append(this.noticesSent).append("<br>");
				html.append("</body></html>");
				var htmlResponse = html.toString();
				exchange.sendResponseHeaders(200, htmlResponse.length());
				var outputStream = exchange.getResponseBody();
				outputStream.write(htmlResponse.getBytes());
				outputStream.flush();
				outputStream.close();
				exchange.close();
			} catch (Exception e) {
				e.printStackTrace();
				exchange.close();
			}
		}

		private String formatData(long bytes) {
			if (bytes < 1024)
				return "%dB".formatted(bytes);
			else if (bytes < 1024 * 1024)
				return "%fKB".formatted(bytes / (1024.0));
			else if (bytes < 1024 * 1024 * 1024)
				return "%fMB".formatted(bytes / (1024.0 * 1024));
			else if (bytes < 1024 * 1024 * 1024 * 1024)
				return "%fGB".formatted(bytes / (1024.0 * 1024 * 1024));
			return "A lot of bytes: %d".formatted(bytes);
		}
	}

	public static void main(final String[] args) throws IOException, IrcException {
		final var gsonBuilder = new GsonBuilder();
		final var gson = gsonBuilder.create();
		I2MConfiguration config;
		try (var bufferedReader = new BufferedReader(
				new FileReader(System.getProperty("user.home") + "/.i2m.json"))) {
			config = gson.fromJson(bufferedReader, I2MConfiguration.class);
		}
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(8000), 50);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-5);
			return;
		}
		new Thread(() -> {
			server.setExecutor(null);
			server.start();
		}).start();
		final var client = new StandaloneClient.Builder().domain(config.homeserverHostname).build();
		final var response = client.auth().login(config.botName, config.botPassword.toCharArray());
		final var listener = new AppListener(client, config, server);
		final var ircConfig = new Configuration.Builder().setName(config.ircUserName)
				.addServer(config.ircHostName)
				.addAutoJoinChannel(config.channelToJoin).addListener(listener).buildConfiguration();
		final var bot = new PircBotX(ircConfig);
		listener.setBot(bot);
		bot.startBot();
	}
}
