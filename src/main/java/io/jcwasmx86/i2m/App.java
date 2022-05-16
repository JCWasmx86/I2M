package io.jcwasmx86.i2m;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.github.ma1uta.matrix.event.message.Emote;
import io.github.ma1uta.matrix.event.message.Notice;
import io.github.ma1uta.matrix.event.message.ServerNotice;
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

		public String getHomeserverHostname() {
			return homeserverHostname;
		}

		public void setHomeserverHostname(final String homeserverHostname) {
			this.homeserverHostname = homeserverHostname;
		}

		public String getBotName() {
			return botName;
		}

		public void setBotName(final String botName) {
			this.botName = botName;
		}

		public String getBotPassword() {
			return botPassword;
		}

		public void setBotPassword(final String botPassword) {
			this.botPassword = botPassword;
		}

		public String getIrcUserName() {
			return ircUserName;
		}

		public void setIrcUserName(final String ircUserName) {
			this.ircUserName = ircUserName;
		}

		public String getIrcHostName() {
			return ircHostName;
		}

		public void setIrcHostName(final String ircHostName) {
			this.ircHostName = ircHostName;
		}

		public String getChannelToJoin() {
			return channelToJoin;
		}

		public void setChannelToJoin(final String channelToJoin) {
			this.channelToJoin = channelToJoin;
		}

		public String getMatrixChannelName() {
			return matrixChannelName;
		}

		public void setMatrixChannelName(final String matrixChannelName) {
			this.matrixChannelName = matrixChannelName;
		}

		public String getMatrixUserName() {
			return matrixUserName;
		}

		public void setMatrixUserName(final String matrixUserName) {
			this.matrixUserName = matrixUserName;
		}

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
			var content = event.getContent();
			if (content instanceof Emote) {
				var re = (RoomEvent<?>) event;
				final var emote = (Emote) event.getContent();
				final var body = emote.getBody();

				if (!re.getSender().equals(this.config.matrixUserName)) {
					return;
				}
				if (re.getOriginServerTs() <= this.startTime) {
					return;
				}
				bot.send().action(this.config.channelToJoin, body);
			}
			if (content instanceof Text) {
				var re = (RoomEvent<?>) event;
				final var txt = (Text) content;
				final var body = txt.getBody();

				if (!re.getSender().equals(this.config.matrixUserName)) {
					return;
				}
				if (re.getOriginServerTs() <= this.startTime) {
					return;
				}
				if (body.equalsIgnoreCase("@members")) {
					this.client.event().sendFormattedMessage(this.id, "Members",
						"Members:\n" + bot.getUserChannelDao().getChannel(this.config.channelToJoin).getUsers().stream().map(a -> "- " + a.getNick() + "\n").reduce("", (a, b) -> a + b));
					return;
				}
				bytesReceived += body.getBytes().length;
				this.messagesSent++;
				bot.send().message(this.config.channelToJoin, body);
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

		public void setBot(final PircBotX bot) {
			this.bot = bot;
		}

		@Override
		public void handle(final HttpExchange exchange) throws IOException {
			try {
				final var now = System.currentTimeMillis();
				final var uptimeInSeconds = (now - this.startTime) / 1000;
				final var times = uptimeInSeconds % (24 * 60 * 60);
				final var days = (uptimeInSeconds - times) / (24 * 60 * 60);
				final var hours = (times - (times % 3600)) / 3600;
				final var secondsLeft = times % 3600;
				final var minutes = (secondsLeft - (secondsLeft % 60)) / 60;
				final var seconds = (int) (secondsLeft % 60);
				final var timestamp = "%d days, %d hours, %d minutes, %d seconds".formatted((int) days,
					(int) hours, (int) minutes, seconds);
				final var html = new StringBuilder();
				html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\" name=\"viewport\" content= \"width=device-width, initial-scale=1.0\"></head>")
					.append("<body>");

				html.append("<b>Uptime: </b>").append(timestamp).append("<br>");

				html.append("<b>Sent data: </b>").append(this.formatData(this.bytesSent))
					.append("<br>");
				html.append("<b>Received data: </b>").append(this.formatData(this.bytesReceived))
					.append("<br>");
				html.append("<b>Messages sent: </b>").append(this.messagesSent).append("<br>");
				html.append("<b>Messages received: </b>").append(this.messagesReceived).append("<br>");
				html.append("<b>Notices sent: </b>").append(this.noticesSent).append("<br>");
				html.append("</body></html>");
				final var htmlResponse = html.toString();
				exchange.sendResponseHeaders(200, htmlResponse.length());
				final var outputStream = exchange.getResponseBody();
				outputStream.write(htmlResponse.getBytes());
				outputStream.flush();
				outputStream.close();
				exchange.close();
			} catch (final Exception e) {
				e.printStackTrace();
				exchange.close();
			}
		}

		private String formatData(final long bytes) {
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
		} catch (final IOException e) {
			e.printStackTrace();
			System.exit(-5);
			return;
		}
		new Thread(() -> {
			server.setExecutor(null);
			server.start();
		}).start();
		final var client = new StandaloneClient.Builder().domain(config.homeserverHostname).build();
		client.auth().login(config.botName, config.botPassword.toCharArray());
		final var listener = new AppListener(client, config, server);
		final var ircConfig = new Configuration.Builder().setName(config.ircUserName)
			.addServer(config.ircHostName)
			.addAutoJoinChannel(config.channelToJoin).addListener(listener).buildConfiguration();
		final var bot = new PircBotX(ircConfig);
		listener.setBot(bot);
		bot.startBot();
	}
}
