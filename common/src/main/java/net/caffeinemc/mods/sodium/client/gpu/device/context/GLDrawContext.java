package net.caffeinemc.mods.sodium.client.gpu.device.context;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class GLDrawContext extends DrawContext {
    private int currentProgram = -1;
    private int offsetLoc = -1;
    private int timeLoc = -1;
    private int idLoc = -1;

    @Override
    public void setContext(RenderPass pass, RenderPipeline pipeline) {
        this.pass = pass;
        this.currentProgram = -1;
    }

    private void updateLocations() {
        int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (prog != this.currentProgram) {
            this.currentProgram = prog;
            if (prog > 0) {
                this.offsetLoc = GL20.glGetUniformLocation(prog, "u_RegionOffset");
                this.timeLoc = GL20.glGetUniformLocation(prog, "u_CurrentTime");
                this.idLoc = GL20.glGetUniformLocation(prog, "u_RegionID");
            } else {
                this.offsetLoc = -1;
                this.timeLoc = -1;
                this.idLoc = -1;
            }
        }
    }

    @Override
    public void pushConstants(float x, float y, float z, int currentTime, int regionId) {
        updateLocations();
        if (this.offsetLoc != -1) {
            GL20.glUniform3f(this.offsetLoc, x, y, z);
        }
        if (this.timeLoc != -1) {
            GL20.glUniform1i(this.timeLoc, currentTime);
        }
        if (this.idLoc != -1) {
            GL30.glUniform1ui(this.idLoc, regionId);
        }
    }

    @Override
    public void rotate() {

    }

    @Override
    public void delete() {

    }

    @Override
    public void endDraw() {

    }
}
