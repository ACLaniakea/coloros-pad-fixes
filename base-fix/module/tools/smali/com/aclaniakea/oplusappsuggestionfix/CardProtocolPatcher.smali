.class public final Lcom/aclaniakea/oplusappsuggestionfix/CardProtocolPatcher;
.super Ljava/lang/Object;
.source "CardProtocolPatcher.java"


# static fields
.field private static final DB_PATH:Ljava/lang/String; = "/data/user/0/com.oplus.pantanal.ums/databases/card_configs"


# direct methods
.method private constructor <init>()V
    .registers 1

    .line 12
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method private static countMismatches(Landroid/database/sqlite/SQLiteDatabase;)J
    .registers 7

    .line 15
    nop

    .line 16
    const-string v0, "SELECT count(*) FROM AppLocalCardConfig WHERE type IN (82,83) AND packageName=\'com.heytap.speechassist\' AND componentName=\'com.heytap.speechassist.home.skillmarket.receiver.BreenoCardWidgetProvider\' AND protocol<>2"

    const/4 v1, 0x0

    invoke-static {p0, v0, v1}, Landroid/database/DatabaseUtils;->longForQuery(Landroid/database/sqlite/SQLiteDatabase;Ljava/lang/String;[Ljava/lang/String;)J

    move-result-wide v2

    const-wide/16 v4, 0x0

    add-long/2addr v2, v4

    .line 22
    const-string v0, "SELECT count(*) FROM CardConfig WHERE type IN (82,83) AND serviceId=\'536875910\' AND protocol<>2"

    invoke-static {p0, v0, v1}, Landroid/database/DatabaseUtils;->longForQuery(Landroid/database/sqlite/SQLiteDatabase;Ljava/lang/String;[Ljava/lang/String;)J

    move-result-wide v4

    add-long/2addr v2, v4

    .line 25
    const-string v0, "SELECT count(*) FROM CloudCardConfig WHERE type IN (82,83) AND serviceId=\'536875910\' AND protocol<>2"

    invoke-static {p0, v0, v1}, Landroid/database/DatabaseUtils;->longForQuery(Landroid/database/sqlite/SQLiteDatabase;Ljava/lang/String;[Ljava/lang/String;)J

    move-result-wide v0

    add-long/2addr v2, v0

    .line 28
    return-wide v2
.end method

.method public static main([Ljava/lang/String;)V
    .registers 8

    .line 32
    nop

    .line 34
    const/4 p0, 0x0

    :try_start_2
    new-instance v0, Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;

    invoke-direct {v0}, Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;-><init>()V

    .line 35
    const/4 v1, 0x0

    invoke-virtual {v0, v1}, Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;->addOpenFlags(I)Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;

    move-result-object v0

    const-string v2, "WAL"

    .line 36
    invoke-virtual {v0, v2}, Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;->setJournalMode(Ljava/lang/String;)Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;

    move-result-object v0

    const-string v2, "NORMAL"

    .line 37
    invoke-virtual {v0, v2}, Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;->setSynchronousMode(Ljava/lang/String;)Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;

    move-result-object v0

    .line 38
    invoke-virtual {v0}, Landroid/database/sqlite/SQLiteDatabase$OpenParams$Builder;->build()Landroid/database/sqlite/SQLiteDatabase$OpenParams;

    move-result-object v0

    .line 39
    new-instance v2, Ljava/io/File;

    const-string v3, "/data/user/0/com.oplus.pantanal.ums/databases/card_configs"

    invoke-direct {v2, v3}, Ljava/io/File;-><init>(Ljava/lang/String;)V

    invoke-static {v2, v0}, Landroid/database/sqlite/SQLiteDatabase;->openDatabase(Ljava/io/File;Landroid/database/sqlite/SQLiteDatabase$OpenParams;)Landroid/database/sqlite/SQLiteDatabase;

    move-result-object p0

    .line 40
    invoke-static {p0}, Lcom/aclaniakea/oplusappsuggestionfix/CardProtocolPatcher;->countMismatches(Landroid/database/sqlite/SQLiteDatabase;)J

    move-result-wide v2

    .line 41
    const-wide/16 v4, 0x0

    cmp-long v0, v2, v4

    if-nez v0, :cond_3b

    .line 42
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;

    const-string v6, "ALREADY_OK protocol=2"

    invoke-virtual {v0, v6}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    .line 43
    invoke-static {v1}, Ljava/lang/System;->exit(I)V

    .line 46
    :cond_3b
    invoke-virtual {p0}, Landroid/database/sqlite/SQLiteDatabase;->beginTransaction()V
    :try_end_3e
    .catchall {:try_start_2 .. :try_end_3e} :catchall_9a

    .line 48
    :try_start_3e
    const-string v0, "UPDATE AppLocalCardConfig SET protocol=2 WHERE type IN (82,83) AND packageName=\'com.heytap.speechassist\' AND componentName=\'com.heytap.speechassist.home.skillmarket.receiver.BreenoCardWidgetProvider\' AND protocol<>2"

    invoke-virtual {p0, v0}, Landroid/database/sqlite/SQLiteDatabase;->execSQL(Ljava/lang/String;)V

    .line 53
    const-string v0, "UPDATE CardConfig SET protocol=2 WHERE type IN (82,83) AND serviceId=\'536875910\' AND protocol<>2"

    invoke-virtual {p0, v0}, Landroid/database/sqlite/SQLiteDatabase;->execSQL(Ljava/lang/String;)V

    .line 55
    const-string v0, "UPDATE CloudCardConfig SET protocol=2 WHERE type IN (82,83) AND serviceId=\'536875910\' AND protocol<>2"

    invoke-virtual {p0, v0}, Landroid/database/sqlite/SQLiteDatabase;->execSQL(Ljava/lang/String;)V

    .line 57
    invoke-virtual {p0}, Landroid/database/sqlite/SQLiteDatabase;->setTransactionSuccessful()V
    :try_end_50
    .catchall {:try_start_3e .. :try_end_50} :catchall_95

    .line 59
    :try_start_50
    invoke-virtual {p0}, Landroid/database/sqlite/SQLiteDatabase;->endTransaction()V

    .line 60
    nop

    .line 62
    invoke-static {p0}, Lcom/aclaniakea/oplusappsuggestionfix/CardProtocolPatcher;->countMismatches(Landroid/database/sqlite/SQLiteDatabase;)J

    move-result-wide v0

    .line 63
    cmp-long v4, v0, v4

    if-nez v4, :cond_7c

    .line 66
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;

    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    const-string v4, "CHANGED protocol=2 rows="

    invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1, v2, v3}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;

    move-result-object v1

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    .line 67
    const/16 v0, 0xa

    invoke-static {v0}, Ljava/lang/System;->exit(I)V

    .line 72
    if-eqz p0, :cond_a9

    .line 73
    goto :goto_a6

    .line 64
    :cond_7c
    new-instance v2, Ljava/lang/IllegalStateException;

    new-instance v3, Ljava/lang/StringBuilder;

    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V

    const-string v4, "Protocol rows still incorrect: "

    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    move-result-object v3

    invoke-virtual {v3, v0, v1}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;

    move-result-object v0

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-direct {v2, v0}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v2

    .line 59
    :catchall_95
    move-exception v0

    invoke-virtual {p0}, Landroid/database/sqlite/SQLiteDatabase;->endTransaction()V

    .line 60
    throw v0
    :try_end_9a
    .catchall {:try_start_50 .. :try_end_9a} :catchall_9a

    .line 68
    :catchall_9a
    move-exception v0

    .line 69
    :try_start_9b
    sget-object v1, Ljava/lang/System;->err:Ljava/io/PrintStream;

    invoke-virtual {v0, v1}, Ljava/lang/Throwable;->printStackTrace(Ljava/io/PrintStream;)V

    .line 70
    const/4 v0, 0x1

    invoke-static {v0}, Ljava/lang/System;->exit(I)V
    :try_end_a4
    .catchall {:try_start_9b .. :try_end_a4} :catchall_aa

    .line 72
    if-eqz p0, :cond_a9

    .line 73
    :goto_a6
    invoke-virtual {p0}, Landroid/database/sqlite/SQLiteDatabase;->close()V

    .line 76
    :cond_a9
    return-void

    .line 72
    :catchall_aa
    move-exception v0

    if-eqz p0, :cond_b0

    .line 73
    invoke-virtual {p0}, Landroid/database/sqlite/SQLiteDatabase;->close()V

    .line 75
    :cond_b0
    throw v0
.end method
