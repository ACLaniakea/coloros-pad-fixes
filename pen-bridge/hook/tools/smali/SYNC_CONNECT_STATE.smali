.method public static syncSettingsConnectState(Landroid/content/Context;Landroid/content/Intent;)V
    .registers 7

    if-eqz p1, :cond_ret

    const-string v0, "connected"

    invoke-virtual {p1, v0}, Landroid/content/Intent;->hasExtra(Ljava/lang/String;)Z

    move-result v1

    if-eqz v1, :cond_ret

    const/4 v1, -0x1

    invoke-virtual {p1, v0, v1}, Landroid/content/Intent;->getIntExtra(Ljava/lang/String;I)I

    move-result v0

    if-ltz v0, :cond_ret

    const/4 v1, 0x2

    if-le v0, v1, :cond_ret

    sget-object v1, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->settingsCallback:Ljava/lang/Object;

    if-eqz v1, :cond_ret

    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v0

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object v2

    if-eqz v2, :cond_ret

    iget-object v2, v2, Lcom/aclaniakea/colorosporttuning/PenState;->address:Ljava/lang/String;

    const/4 v3, 0x2

    new-array v3, v3, [Ljava/lang/Object;

    const/4 v4, 0x0

    aput-object v0, v3, v4

    const/4 v4, 0x1

    aput-object v2, v3, v4

    const-string v4, "notifyConnectState"

    invoke-static {v1, v4, v3}, Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->invoke(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_ret

    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V

    const-string v3, "IPe settings connect state pushed state="

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_ret
    return-void
.end method
