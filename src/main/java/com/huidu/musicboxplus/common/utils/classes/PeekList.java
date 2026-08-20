package com.huidu.musicboxplus.common.utils.classes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PeekList<T> {
    private final List<T> list;
    private final boolean hasEnd;
    private final Lock lock = new ReentrantLock();
    private volatile int current = 0;

    public PeekList(List<T> source) {
        this(source, false);
    }

    public PeekList(List<T> source, boolean hasEnd) {
        this.list = source;
        this.hasEnd = hasEnd;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public List<T> getNextElements(int size) {
        this.lock.lock();
        try {
            int currentLast = this.current;
            ArrayList<T> result = new ArrayList<T>(size);
            for (int i = 0; i < size && this.nextInternal(); ++i) {
                result.add(this.current());
            }
            this.current = currentLast;
            return result;
        }
        finally {
            this.lock.unlock();
        }
    }

    public boolean hasNext() {
        if (this.hasEnd) {
            return this.current < this.list.size() - 1;
        }
        return true;
    }

    public boolean hasPrev() {
        if (this.hasEnd) {
            return this.current > 0;
        }
        return true;
    }

    public T current() {
        return this.list.get(this.current);
    }

    public T getAndNext() {
        T c = this.current();
        this.next();
        return c;
    }


    public boolean next() {
        this.lock.lock();
        try {
            return this.nextInternal();
        }
        finally {
            this.lock.unlock();
        }
    }

    private boolean nextInternal() {
        if (this.hasNext()) {
            ++this.current;
            if (this.current == this.list.size()) {
                this.current = 0;
            }
            return true;
        }
        return false;
    }

    public boolean prev() {
        this.lock.lock();
        try {
            if (this.hasPrev()) {
                --this.current;
                if (this.current <= -1) {
                    this.current = this.list.size() - 1;
                }
                return true;
            }
            return false;
        }
        finally {
            this.lock.unlock();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public List<T> getPrevElements(int size) {
        this.lock.lock();
        try {
            int currentLast = this.current;
            ArrayList<T> result = new ArrayList<T>(size);
            for (int i = 0; i < size && this.prev(); ++i) {
                result.add(this.current());
            }
            this.current = currentLast;
            return result;
        }
        finally {
            this.lock.unlock();
        }
    }

    public int getIndexOf(T element) {
        return this.list.indexOf(element);
    }

    public void moveTo(T element) {
        int index = this.list.indexOf(element);
        if (index != -1) {
            this.current = index;
        }
    }

    public void reset() {
        this.lock.lock();
        try {
            this.current = 0;
        }
        finally {
            this.lock.unlock();
        }
    }

    public List<T> getList() {
        return this.list;
    }

    public boolean isHasEnd() {
        return this.hasEnd;
    }


    public int getCurrent() {
        return this.current;
    }
}

