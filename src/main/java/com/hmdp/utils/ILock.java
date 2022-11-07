package com.hmdp.utils;

public interface ILock {

    public boolean tryLock(Long time);

    public void unLock();
}
