package hsts.common.protocol;

import java.io.Serializable;

/**
 * One line on a member of staff's to-do list.
 *
 * <h2>Why it carries where to go</h2>
 *
 * <p>A list that says "12 papers to mark" and leaves her to find the marking screen
 * has told her something she mostly knew. Each line names the screen it is about,
 * so the list can open it.</p>
 *
 * <h2>Waiting on her, or waiting on somebody else</h2>
 *
 * <p>Both belong on the list and they are not the same thing. "3 papers to mark" is
 * hers to do; "2 exams with the coordinator" is not - but she asked for it, it is
 * why her exam has not appeared yet, and leaving it off would send her looking for
 * it. {@link #isMine()} keeps them apart so the screen can too.</p>
 */
public class TodoItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String title;
    private final String detail;
    private final int count;
    private final boolean mine;
    private final String screen;

    public TodoItem(String title, String detail, int count, boolean mine, String screen) {
        this.title = title;
        this.detail = detail;
        this.count = count;
        this.mine = mine;
        this.screen = screen;
    }

    /** "Mark 12 papers". */
    public String getTitle() {
        return title;
    }

    /** The sentence under it, saying where they came from. */
    public String getDetail() {
        return detail;
    }

    public int getCount() {
        return count;
    }

    /** True when she is the one who has to act. */
    public boolean isMine() {
        return mine;
    }

    /** The screen this line is about, e.g. {@code /fxml/Grading.fxml}. */
    public String getScreen() {
        return screen;
    }

    @Override
    public String toString() {
        return count + "  " + title;
    }
}
