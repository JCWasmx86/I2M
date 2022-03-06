package io.jcwasmx86.i2m;

import io.github.ma1uta.matrix.client.StandaloneClient;
import io.github.ma1uta.matrix.event.RoomEvent;
import io.github.ma1uta.matrix.event.message.Text;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.Event;
import org.pircbotx.hooks.Listener;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.QuitEvent;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.Executors;

public class App {
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
	static class AppListener implements Listener {

		private final StandaloneClient client;
		private final long startTime;
		private String id;
		private PircBotX bot;
		private I2MConfiguration config;

		public AppListener(StandaloneClient client, I2MConfiguration config) {
			this.client = client;
			this.config = config;
			this.id = this.client.room().joinByIdOrAlias(config.matrixChannelName, null, null).getRoomId();
			this.startTime = System.currentTimeMillis();
			var executor = Executors.newFixedThreadPool(1);
			executor.submit(() -> {
				String batch = null;
				while (true) {
					var response = this.client.sync().sync(null, batch, true, "online", 10 * 1000l);
					batch = response.getNextBatch();
					var rooms = response.getRooms();
					if (rooms == null)
						continue;
					var join = rooms.getJoin();
					if (join == null)
						continue;
					for (var room : join.entrySet()) {
						var joined = room.getValue();
						if (joined.getState() != null && joined.getState().getEvents() != null) {
							joined.getState().getEvents().forEach(this::evalEvent);
						}
						var timeline = joined.getTimeline();
						if (timeline != null && timeline.getEvents() != null) {
							timeline.getEvents().forEach(this::evalEvent);
						}
					}
					Thread.sleep(1000);
				}
			});
		}

		private void evalEvent(io.github.ma1uta.matrix.event.Event event) {
			if (event instanceof RoomEvent<?>) {
				var re = (RoomEvent<?>)event;
				var content = re.getContent();
				if (content instanceof Text) {
					var rmc = (Text)content;
					var body = rmc.getBody();

					if (!re.getSender().equals(this.config.matrixUserName)) {
						return;
					}
					if (re.getOriginServerTs() <= this.startTime) {
						return;
					}
					bot.send().message(this.config.channelToJoin, body);
				}
			}
		}

		@Override
		public void onEvent(Event event) throws Exception {
			if (event instanceof MessageEvent) {
				var me = (MessageEvent)event;
				var messageAsString = "[" + me.getUser().getNick() + "] " + me.getMessage();
				this.client.event().sendMessage(this.id, messageAsString);
			} else if (event instanceof JoinEvent) {
				var je = (JoinEvent)event;
				var notice = je.getUser().getNick() + " joined!";
				this.client.event().sendNotice(this.id, notice);
			} else if (event instanceof QuitEvent) {
				var qe = (QuitEvent)event;
				var notice = qe.getUser().getNick() + " left: " + qe.getReason();
				this.client.event().sendNotice(this.id, notice);
			}
		}

		public void setBot(PircBotX bot) {
			this.bot = bot;
		}
	}

	public static void main(String[] args) throws IOException, IrcException {
		var gsonBuilder = new GsonBuilder ();
		var gson = gsonBuilder.create ();
		I2MConfiguration config;
		try(var bufferedReader = new BufferedReader(new FileReader(System.getProperty("user.home") + "/.i2m.json"))) {
			config = gson.fromJson(bufferedReader, I2MConfiguration.class);
		}
		var client = new StandaloneClient.Builder().domain(config.homeserverHostname).build();
		var response = client.auth().login(config.botName, config.botPassword.toCharArray());
		var listener = new AppListener(client, config);
		var ircConfig = new Configuration.Builder().setName(config.ircUserName).addServer(config.ircHostName).addAutoJoinChannel(config.channelToJoin).addListener(listener).buildConfiguration();
		var bot = new PircBotX(ircConfig);
		listener.setBot(bot);
		bot.startBot();
	}
}
