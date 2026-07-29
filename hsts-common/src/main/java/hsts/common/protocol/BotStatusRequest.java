package hsts.common.protocol;

import hsts.common.enums.BotStatus;

import java.io.Serializable;

/** Turning a bot on or off (requirement 60). */
public class BotStatusRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int botId;
    private final BotStatus status;

    public BotStatusRequest(int botId, BotStatus status) {
        this.botId = botId;
        this.status = status;
    }

    public int getBotId()        { return botId; }
    public BotStatus getStatus() { return status; }
}
