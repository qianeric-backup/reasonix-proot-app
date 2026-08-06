/*
 * pty-bridge.c ¡ª¡ª ¾²Ì¬Á´½ÓµÄ PTY ÇÅ½ÓÆ÷£¨musl£¬Android ¿ÉÖ±½ÓÖ´ÐÐ£©¡£
 *
 * ÓÃ·¨: pty-bridge <program> [args...]
 *
 * ¹¤×÷·½Ê½£º
 *   1. ´´½¨ PTY£¨posix_openpt + fork£©
 *   2. ×Ó½ø³Ì³ÉÎª»á»°Ê×Áì£¬°Ñ stdin/stdout/stderr ½Óµ½ PTY slave£¬È»ºó exec Ä¿±ê³ÌÐò£¨Èç proot£©
 *   3. ¸¸½ø³Ì°Ñ×ÔÉí stdin£¨À´×Ô Android ¹ÜµÀ£©×ª·¢µ½ PTY master£¬
 *      ²¢°Ñ PTY master µÄÊä³ö×ª·¢µ½×ÔÉí stdout£¨»Øµ½ Android ¹ÜµÀ£©
 *
 * ÕâÑù Android Ó¦ÓÃ¿ÉÒÔ¼ÌÐøÊ¹ÓÃ±ê×¼ Process ¹ÜµÀÓë×Ó½ø³ÌÍ¨ÐÅ£¬
 * ¶ø guest ÄÚµÄ TUI ³ÌÐò£¨reasonix£©ÄÃµ½µÄÊÇÕæÊµ TTY¡£
 * ±àÒë£¨zig£¬²ú³ö¾²Ì¬ PIE£©£º
 *   zig cc -target aarch64-linux-musl -O2 -static -pie -fPIE -o pty-bridge pty-bridge.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/select.h>
#include <signal.h>
#include <errno.h>

#define ROWS 40
#define COLS 120

static pid_t g_child = 0;

static void on_term(int sig) {
    (void)sig;
    if (g_child > 0) {
        kill(-g_child, SIGKILL);   /* Õû¸ö guest ½ø³Ì×é */
        kill(g_child, SIGKILL);
    }
    _exit(130);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: pty-bridge <program> [args...]\n");
        return 2;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) { perror("posix_openpt"); return 1; }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        perror("grantpt/unlockpt");
        return 1;
    }
    char *sname = ptsname(master);
    int slave = open(sname, O_RDWR | O_NOCTTY);
    if (slave < 0) { perror("open slave"); return 1; }

    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 1; }

    if (pid == 0) {
        /* ×Ó½ø³Ì */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        execv(argv[1], &argv[1]);
        perror("execv");
        _exit(127);
    }

    g_child = pid;
    close(slave);

    signal(SIGTERM, on_term);
    signal(SIGHUP, on_term);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = ROWS;
    ws.ws_col = COLS;
    ioctl(master, TIOCSWINSZ, &ws);

    /* Ë«Â·ÇÅ½Ó£ºstdin -> master£¬master -> stdout */
    /* stdin ÉÏµÄ´øÍâ resize Ð­Òé£º]50;ROWS;COLS -> ioctl(TIOCSWINSZ)£¬
     * ÓÃÓÚ xterm.js ×ÔÊÊÓ¦ÆÁÄ»ºóÍ¬²½ PTY ´°¿Ú´óÐ¡£¨TUI ²¼¾ÖÒÀÀµ£©¡£ */
    char buf[8192];
    int stdin_open = 1;
    for (;;) {
        fd_set rfds;
        FD_ZERO(&rfds);
        if (stdin_open) FD_SET(0, &rfds);
        FD_SET(master, &rfds);
        int maxfd = master > 0 ? master : 0;

        int r = select(maxfd + 1, &rfds, NULL, NULL, NULL);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (stdin_open && FD_ISSET(0, &rfds)) {
            ssize_t n = read(0, buf, sizeof(buf));
            if (n <= 0) {
                stdin_open = 0;              /* Android ¹ÜµÀ¹Ø±Õ£ºÍ£Ö¹×ª·¢ÊäÈë */
            } else {
                int consumed = 0;
                while (consumed < n) {
                    /* ¼ì²â resize ÐòÁÐÆðÊ¼£ºESC ] 5 0 ; */
                    if (buf[consumed] == 0x1b && consumed + 4 < n
                        && buf[consumed+1] == ']' && buf[consumed+2] == '5'
                        && buf[consumed+3] == '0' && buf[consumed+4] == ';') {
                        int end = -1, i;
                        for (i = consumed + 5; i < n; i++)
                            if (buf[i] == 0x07) { end = i; break; }
                        if (end < 0) {
                            /* ÐòÁÐ²»ÍêÕû£¨¼«º±¼û£¬resize ÐòÁÐºÜ¶Ì£©£º¶ªÆúÊ£Óà£¬µÈ´ýÏÂ¶Î */
                            consumed = (int)n;
                            break;
                        }
                        int rows = 0, cols = 0, j = consumed + 5;
                        while (j < end && buf[j] >= '0' && buf[j] <= '9')
                            rows = rows * 10 + (buf[j++] - '0');
                        if (j < end && buf[j] == ';') j++;
                        while (j < end && buf[j] >= '0' && buf[j] <= '9')
                            cols = cols * 10 + (buf[j++] - '0');
                        if (rows > 0 && cols > 0) {
                            struct winsize ws;
                            memset(&ws, 0, sizeof(ws));
                            ws.ws_row = (unsigned short)rows;
                            ws.ws_col = (unsigned short)cols;
                            ioctl(master, TIOCSWINSZ, &ws);
                        }
                        consumed = end + 1;
                    } else {
                        /* ÆÕÍ¨Êý¾Ý£º×ª·¢µ½ÏÂÒ»¸ö¿ÉÄÜµÄÐòÁÐÆðÊ¼ */
                        int next = consumed + 1;
                        while (next < n
                               && !(buf[next] == 0x1b && next + 4 < n
                                    && buf[next+1] == ']' && buf[next+2] == '5'
                                    && buf[next+3] == '0' && buf[next+4] == ';'))
                            next++;
                        if (write(master, buf + consumed, (size_t)(next - consumed)) < 0) {
                            consumed = (int)n;
                            break;
                        }
                        consumed = next;
                    }
                }
            }
        }
        if (FD_ISSET(master, &rfds)) {
            ssize_t n = read(master, buf, sizeof(buf));
            if (n <= 0) break;               /* ×Ó½ø³ÌÍË³ö -> PTY ¹Ø±Õ */
            if (write(1, buf, (size_t)n) < 0) break;
        }
    }

    int status;
    waitpid(pid, &status, 0);
    return 0;
}
