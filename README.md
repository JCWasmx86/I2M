# I2M

A very, very simple IRC2Matrix-Bridge.

## Setup



`~/.i2m.json`:
```
{
	"homeserverHostname" : "homeserver.tld",
	"botName" : "your-bot-name",
	"botPassword" : "your-bot-password",
	"ircUserName": "howYouWillBeCalledInTheIRCChannel",
	"ircHostName": "irc.server.tld",
	"channelToJoin": "#channelName",
	"matrixChannelName": "#matrixChannelName:homeserver.tld",
	"matrixUserName": "@yourMatrixName:homeserver.tld",
}
```
And then execute:
```
mvn package
mvn exec:java
```
