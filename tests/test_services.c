/* System services contracts: dialogs (progress bars run until closed), PlayGo, trophies. */
#undef NDEBUG /* the checks are asserts; release builds define NDEBUG */
#include "../src/runtime_services.c"
#include <assert.h>
#include <unistd.h>

uintptr_t runtime_lookup(const RuntimeExport *table, size_t count, const char *nid) { (void)table; (void)count; (void)nid; return 0; }
int gow3gpu_text_input_begin(const char *text, const char *prompt) { (void)text; (void)prompt; return 0; }
int gow3gpu_text_input_poll(char *text, uint64_t size) { (void)text; (void)size; return 0; }
int64_t runtime_file_open(const char *path, int flags, int mode) { (void)path; (void)flags; (void)mode; return -1; }
int64_t runtime_file_read(int fd, void *buffer, uint64_t size) { (void)fd; (void)buffer; (void)size; return -1; }
int64_t runtime_file_close(int fd) { (void)fd; return 0; }
static int choice_next=-1;
static int choice_cancelled=0;
int gow3gpu_choice_begin(const char *title, const char *const *items, const char *const *images, int count, int focus) { (void)title; (void)items; (void)images; (void)focus; return count>0; }
int gow3gpu_choice_poll(void) { return choice_next; }
void gow3gpu_choice_cancel(void) { choice_cancelled=1; }
/* 存档元数据桩:用例填好这 1328 字节,save_label 从里面取标题(offset 0)与副标题(offset 128)。
 * saved_param_ready 为 0 时保持原来的"没有元数据"行为,让既有用例继续退回目录名。 */
static unsigned char saved_param[RUNTIME_SAVE_PARAM_SIZE];
static int saved_param_ready=0;
static void set_save_param(const char *title, const char *subtitle) {
    memset(saved_param,0,sizeof(saved_param));
    if (title) strncpy((char *)saved_param,title,SAVE_TEXT_FIELD-1);
    if (subtitle) strncpy((char *)saved_param+SAVE_TEXT_FIELD,subtitle,SAVE_TEXT_FIELD-1);
    saved_param_ready=1;
}
int runtime_savedata_param(int32_t user, const char *dir, void *param) {
    (void)user; (void)dir;
    if (!saved_param_ready) return -1;
    memcpy(param,saved_param,sizeof(saved_param));
    return 0;
}
int runtime_savedata_icon(int32_t user, const char *dir, char *out, size_t size) { (void)user; (void)dir; (void)out; (void)size; return -1; }

int main(void) {
    unsigned char param[128]={0}, result[64]={0}; /* the game zeroes the pointers it does not want */
    uint32_t value;

    /* MsgDialog: a user message finishes with OK; a progress bar runs until closed. */
    assert(msg_init()==0);
    memset(param+56,0,4); param[56]=1;
    assert(msg_open(param)==0 && msg_status()==DIALOG_FINISHED);
    assert(msg_result(result)==0);
    memcpy(&value,result+4,4); assert(value==1);
    param[56]=2;
    assert(msg_open(param)==0 && msg_status()==DIALOG_RUNNING);
    assert(msg_result(result)==DIALOG_NOT_FINISHED);
    assert(msg_progress(0,50)==0 && msg_progress(1,50)==DIALOG_PARAM_INVALID);
    assert(msg_close()==0 && msg_status()==DIALOG_FINISHED && msg_close()==DIALOG_NOT_RUNNING);
    assert(msg_progress(0,100)==DIALOG_NOT_RUNNING);
    assert(msg_result(NULL)==DIALOG_ARG_NULL);
    assert(msg_term()==0 && msg_open(NULL)==DIALOG_ARG_NULL);

    /* SaveDataDialog: mode at offset 52 (5 = progress bar); result after the mode field. */
    assert(save_init()==0 && save_ready()==1);
    param[52]=5;
    assert(save_open(param)==0 && save_status()==DIALOG_RUNNING && save_progress(0,10)==0);
    assert(save_close()==0 && save_result(result)==0);
    memcpy(&value,result+8,4); assert(value==0); /* closed by the game: no button */
    param[52]=2;
    assert(save_open(param)==0 && save_status()==DIALOG_FINISHED && save_result(result)==0);
    memcpy(&value,result+8,4); assert(value==1);
    assert(save_term()==0);

    /* Save list: the player's choice comes back as the save directory. */
    char names[2][32]={"SAVE0001","SAVE0002"}, chosen[32];
    unsigned char items[64]={0}, list[128]={0}, out[64]={0};
    const char *names_ptr=names[0]; uint32_t name_count=2; const char *new_title="New";
    memcpy(items+16,&names_ptr,8); memcpy(items+24,&name_count,4); memcpy(items+32,&new_title,8);
    unsigned char *items_ptr=items; char *chosen_ptr=chosen;
    list[52]=1; list[56]=2; memcpy(list+72,&items_ptr,8);
    memcpy(out+16,&chosen_ptr,8);
    assert(save_init()==0 && save_open(list)==0 && save_status()==DIALOG_RUNNING);
    choice_next=2; /* entries: New save, SAVE0001, SAVE0002 */
    assert(save_status()==DIALOG_FINISHED && save_result(out)==0 && !strcmp(chosen,"SAVE0002"));
    memcpy(&value,out+4,4); assert(value==0);
    memcpy(&value,out+8,4); assert(value==1);
    assert(save_open(list)==0);
    choice_next=0;
    assert(save_status()==DIALOG_FINISHED && save_result(out)==0 && chosen[0]==0); /* new save */
    assert(save_open(list)==0);
    choice_next=-2;
    assert(save_status()==DIALOG_FINISHED && save_result(out)==0);
    memcpy(&value,out+4,4); assert(value==SAVE_RESULT_CANCELED);

    /* Nobody answered the list: the game must get its dialog back instead of waiting forever, and
     * the list must really close (otherwise it keeps drawing and keeps holding the game's input).
     * The deadline is a second here so the test does not have to wait for the real one. */
    setenv("GOW3_SAVE_LIST_TIMEOUT","1",1);
    choice_cancelled=0; choice_next=-1; /* still on screen, nobody has pressed anything */
    assert(save_open(list)==0 && save_status()==DIALOG_RUNNING && !choice_cancelled);
    assert(save_status()==DIALOG_RUNNING && !choice_cancelled); /* still inside the second */
    sleep(2);
    assert(save_status()==DIALOG_FINISHED && choice_cancelled);
    assert(save_result(out)==0);
    memcpy(&value,out+4,4); assert(value==SAVE_RESULT_CANCELED);
    unsetenv("GOW3_SAVE_LIST_TIMEOUT");

    /* A choice that arrives in time is not overridden by the deadline. */
    choice_cancelled=0; choice_next=1;
    assert(save_open(list)==0);
    assert(save_status()==DIALOG_FINISHED && !choice_cancelled);
    assert(save_result(out)==0 && !strcmp(chosen,"SAVE0001"));
    assert(save_term()==0);

    /* 存档标题:游戏写什么语言就显示什么语言,但只有合法 UTF-8 能到达渲染层。
     * 繁中一个汉字 3 字节,这条路径以前是裸指针直读,现在先校验再截断。 */
    char label[200], long_title[128];
    set_save_param("關卡 3","Ymse 開始");
    save_label(label,sizeof(label),"SAVE0001");
    assert(!strncmp(label,"關卡 3",sizeof("關卡 3")-1));
    assert(strstr(label,"Ymse 開始")!=NULL);

    /* Shift-JIS 不是合法 UTF-8:退回目录名,不能把无法解释的字节交出去。 */
    set_save_param("\x82\xA0\x82\xA2","x");
    save_label(label,sizeof(label),"SAVE0001");
    assert(!strncmp(label,"SAVE0001",8));
    assert(strstr(label,"\x82\xA0")==NULL);

    /* 空的标题/副标题同样退回目录名,且不会多出一行空副标题。 */
    set_save_param(NULL,NULL);
    save_label(label,sizeof(label),"SAVE0001");
    assert(!strncmp(label,"SAVE0001",8));
    assert(strchr(label,'\n')==strrchr(label,'\n'));       /* 只有标题和日期两行,没有空副标题行 */

    /* 多字节序列被中途的空字节截断,不能当成合法短串放行。 */
    set_save_param("\xE9\x97\x9C",NULL);
    saved_param[2]=0; saved_param[3]='\x9C';
    save_label(label,sizeof(label),"SAVE0001");
    assert(!strncmp(label,"SAVE0001",8));

    /* 60 字节的显示上限必须落在字符边界上,否则又变回非法 UTF-8。 */
    memset(long_title,'A',59); memcpy(long_title+59,"\xE9\x97\x9C",3); memcpy(long_title+62,"BBB",3);
    long_title[65]=0;
    set_save_param(long_title,NULL);
    save_label(label,sizeof(label),"SAVE0001");
    assert(strcspn(label,"\n")==59);                     /* 半个汉字正好放不下,停在第 59 字节 */
    assert(utf8_field_ok(label,sizeof(label)));
    saved_param_ready=0;                                 /* 其余用例回到"没有元数据" */

    /* 原生桥(GOW3_SAVE_DIALOG_FILE):请求写出、握手、应答、交接超时回退。 */
    const char *bridge="/tmp/gow3-save-dialog.request";
    setenv("GOW3_SAVE_DIALOG_FILE",bridge,1);
    setenv("GOW3_SAVE_DIALOG_HANDOFF_TIMEOUT","1",1);
    unlink(bridge); unlink("/tmp/gow3-save-dialog.request.result");
    unlink("/tmp/gow3-save-dialog.request.tmp");

    /* 请求写出,内容按 design D1:ok / 类型(2 读取) / 条目数 / 每条目 3 行。 */
    assert(save_init()==0 && save_open(list)==0 && save_status()==DIALOG_RUNNING);
    FILE *req=fopen(bridge,"r");
    assert(req);
    {
        char line[64];
        assert(fgets(line,sizeof(line),req) && !strcmp(line,"ok\n"));
        assert(fgets(line,sizeof(line),req) && !strcmp(line,"2\n"));
        assert(fgets(line,sizeof(line),req) && !strcmp(line,"3\n"));
        assert(fgets(line,sizeof(line),req) && !strcmp(line,"New save\n"));
    }
    fclose(req);
    assert(save_dialog_bridge_request_pending(bridge)); /* 请求文件还在 = 没接手 */

    /* 请求文件被删 = launcher 接手,列表进入应答阶段仍保持运行。 */
    unlink(bridge);
    assert(save_status()==DIALOG_RUNNING);

    /* 应答 "ok 2" → 选 SAVE0002(条目:New save/SAVE0001/SAVE0002)。 */
    req=fopen("/tmp/gow3-save-dialog.request.result","w");
    assert(req); fprintf(req,"ok 2\n"); fclose(req);
    assert(save_status()==DIALOG_FINISHED && save_result(out)==0 && !strcmp(chosen,"SAVE0002"));
    memcpy(&value,out+4,4); assert(value==0);
    memcpy(&value,out+8,4); assert(value==1);
    assert(save_term()==0);

    /* 应答 "cancel" → 交还取消结果。 */
    assert(save_init()==0 && save_open(list)==0 && save_status()==DIALOG_RUNNING);
    unlink(bridge);
    assert(save_status()==DIALOG_RUNNING);
    req=fopen("/tmp/gow3-save-dialog.request.result","w");
    assert(req); fprintf(req,"cancel\n"); fclose(req);
    assert(save_status()==DIALOG_FINISHED && save_result(out)==0);
    memcpy(&value,out+4,4); assert(value==SAVE_RESULT_CANCELED);
    assert(save_term()==0);

    /* 应答索引越界(跨进程外部输入被写坏成 ok 99,条目只有 3 个):按取消处理,不得越界读。 */
    assert(save_init()==0 && save_open(list)==0 && save_status()==DIALOG_RUNNING);
    unlink(bridge);
    assert(save_status()==DIALOG_RUNNING);
    req=fopen("/tmp/gow3-save-dialog.request.result","w");
    assert(req); fprintf(req,"ok 99\n"); fclose(req);
    assert(save_status()==DIALOG_FINISHED && save_result(out)==0);
    memcpy(&value,out+4,4); assert(value==SAVE_RESULT_CANCELED);
    assert(save_term()==0);

    /* 无人接手且超过交接超时 → 回退 overlay 列表,选择并入原有分支。 */
    choice_cancelled=0; choice_next=1;
    assert(save_init()==0 && save_open(list)==0 && save_status()==DIALOG_RUNNING);
    sleep(2); /* 超过 1 秒交接超时 */
    assert(save_status()==DIALOG_FINISHED && !choice_cancelled);
    assert(!save_dialog_bridge_request_pending(bridge)); /* 回退后请求文件被删,迟到的 launcher 不会叠对话框 */
    assert(save_result(out)==0 && !strcmp(chosen,"SAVE0001"));
    assert(save_term()==0);

    unsetenv("GOW3_SAVE_DIALOG_FILE");
    unsetenv("GOW3_SAVE_DIALOG_HANDOFF_TIMEOUT");
    unlink(bridge);
    unlink("/tmp/gow3-save-dialog.request.result");
    unlink("/tmp/gow3-save-dialog.request.tmp");

    /* PlayGo: installed, full speed, progress never divides by zero. */
    int32_t handle=0; uint64_t progress[2]; int32_t speed; uint16_t chunk=0;
    assert(playgo_init(NULL)==0 && playgo_open(&handle,NULL)==0);
    assert(playgo_get_speed(handle,&speed)==0 && speed==2);
    assert(playgo_progress(handle,&chunk,1,progress)==0 && progress[0]==progress[1] && progress[1]);
    assert(playgo_progress(handle,&chunk,0,progress)==PLAYGO_BAD_SIZE);
    assert(playgo_todo(handle,&chunk,1)==0 && playgo_todo(handle,NULL,1)==PLAYGO_BAD_POINTER);
    assert(playgo_close(handle)==0 && playgo_close(handle)==PLAYGO_BAD_HANDLE && playgo_terminate()==0);

    /* Trophies: nothing unlocked. */
    uint32_t flags[4]={~0u,~0u,~0u,~0u}, count=7;
    assert(trophy_unlock_state(1,1,flags,&count)==0 && !flags[0] && !flags[3] && count==0);
    assert(trophy_unlock_state(1,1,NULL,&count)==TROPHY_INVALID && trophy_release(0)==TROPHY_INVALID);

    float ratio=0; assert(safe_area(&ratio)==0 && ratio==1.0f && safe_area(NULL)==SYSTEM_PARAMETER);
    puts("services test: ok");
    return 0;
}
