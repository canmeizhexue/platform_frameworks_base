/*
 * Copyright (C) 2005 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_REF_BASE_H
#define ANDROID_REF_BASE_H

#include <cutils/atomic.h>
#include <utils/TextOutput.h>

#include <stdint.h>
#include <sys/types.h>
#include <stdlib.h>

// ---------------------------------------------------------------------------
namespace android {

template<typename T> class wp;

// ---------------------------------------------------------------------------
//宏定义，，，
#define COMPARE_WEAK(_op_)                                      \
inline bool operator _op_ (const sp<T>& o) const {              \
    return m_ptr _op_ o.m_ptr;                                  \
}                                                               \
inline bool operator _op_ (const T* o) const {                  \
    return m_ptr _op_ o;                                        \
}                                                               \
template<typename U>                                            \
inline bool operator _op_ (const sp<U>& o) const {              \
    return m_ptr _op_ o.m_ptr;                                  \
}                                                               \
template<typename U>                                            \
inline bool operator _op_ (const U* o) const {                  \
    return m_ptr _op_ o;                                        \
}

#define COMPARE(_op_)                                           \
COMPARE_WEAK(_op_)                                              \
inline bool operator _op_ (const wp<T>& o) const {              \
    return m_ptr _op_ o.m_ptr;                                  \
}                                                               \
template<typename U>                                            \
inline bool operator _op_ (const wp<U>& o) const {              \
    return m_ptr _op_ o.m_ptr;                                  \
}

// ---------------------------------------------------------------------------
//想实现智能引用的对象，需要继承的类，已经内置了引用计数
class RefBase
{
public:
            void            incStrong(const void* id) const;
            void            decStrong(const void* id) const;
    
            void            forceIncStrong(const void* id) const;

            //! DEBUGGING ONLY: Get current strong ref count.
            int32_t         getStrongCount() const;

    class weakref_type
    {
    public:
        RefBase*            refBase() const;
        
        void                incWeak(const void* id);
        void                decWeak(const void* id);
        
        bool                attemptIncStrong(const void* id);
        
        //! This is only safe if you have set OBJECT_LIFETIME_FOREVER.
        bool                attemptIncWeak(const void* id);

        //! DEBUGGING ONLY: Get current weak ref count.
        int32_t             getWeakCount() const;

        //! DEBUGGING ONLY: Print references held on object.
        void                printRefs() const;

        //! DEBUGGING ONLY: Enable tracking for this object.
        // enable -- enable/disable tracking
        // retain -- when tracking is enable, if true, then we save a stack trace
        //           for each reference and dereference; when retain == false, we
        //           match up references and dereferences and keep only the 
        //           outstanding ones.
        
        void                trackMe(bool enable, bool retain);
    };
    
            weakref_type*   createWeak(const void* id) const;
            
            weakref_type*   getWeakRefs() const;

            //! DEBUGGING ONLY: Print references held on object.
    inline  void            printRefs() const { getWeakRefs()->printRefs(); }

            //! DEBUGGING ONLY: Enable tracking of object.
    inline  void            trackMe(bool enable, bool retain)
    { 
        getWeakRefs()->trackMe(enable, retain); 
    }

    // used to override the RefBase destruction.
    class Destroyer {
        friend class RefBase;
    public:
        virtual ~Destroyer();
    private:
        virtual void destroy(RefBase const* base) = 0;
    };

    // Make sure to never acquire a strong reference from this function. The
    // same restrictions than for destructors apply.
    void setDestroyer(Destroyer* destroyer);

protected:
                            RefBase();
    virtual                 ~RefBase();

    //! Flags for extendObjectLifetime()
    enum {
    	//普通指针的多少是不会影响引用计数的，对于一个对象，可能对应多个强指针，，，
    	//默认是0，只受强引用计数的影响，
    	
    		//目标对象受强引用计数和若引用计数共同影响，也就是说要强引用计数和若引用计数都为0
        OBJECT_LIFETIME_WEAK    = 0x0001,
        //目标对象不受引用计数影响，已经退化成普通指针，需要程序员显式释放
        OBJECT_LIFETIME_FOREVER = 0x0003
    };
    
            void            extendObjectLifetime(int32_t mode);
            
    //! Flags for onIncStrongAttempted()
    enum {
        FIRST_INC_STRONG = 0x0001
    };
    
    virtual void            onFirstRef();
    virtual void            onLastStrongRef(const void* id);
    virtual bool            onIncStrongAttempted(uint32_t flags, const void* id);
    virtual void            onLastWeakRef(const void* id);

private:
    friend class weakref_type;
    class weakref_impl;
    
                            RefBase(const RefBase& o);//构造函数
            RefBase&        operator=(const RefBase& o);//重载=运算符
            
        weakref_impl* const mRefs;//这个变量同时支持强引用计数和弱引用计数，
};

// ---------------------------------------------------------------------------
//轻量级指针，，只有强引用计数，，模板类，T代表指向的对象类型，
//这个类实现了引用计数，，，想实现智能指针功能的类需要实现特定的类，然后配合智能指针进行使用，
template <class T>
class LightRefBase
{
public:
    inline LightRefBase() : mCount(0) { }
    inline void incStrong(const void* id) const {
        android_atomic_inc(&mCount);
    }
    inline void decStrong(const void* id) const {
    	//这个函数返回减去1之前的值，
        if (android_atomic_dec(&mCount) == 1) {
        		
            delete static_cast<const T*>(this);
        }
    }
    //! DEBUGGING ONLY: Get current strong ref count.
    inline int32_t getStrongCount() const {
        return mCount;
    }
    
protected:
    inline ~LightRefBase() { }
    
private:
    mutable volatile int32_t mCount;//引用的次数，
};

// ---------------------------------------------------------------------------
       

        //A. 如果对象的标志位被设置为0，那么只要发现对象的强引用计数值为0，那就会自动delete掉这个对象；

       // B. 如果对象的标志位被设置为OBJECT_LIFETIME_WEAK，那么只有当对象的强引用计数和弱引用计数都为0的时候，才会自动delete掉这个对象；
/
       // C. 如果对象的标志位被设置为OBJECT_LIFETIME_FOREVER，那么对象就永远不会自动被delete掉，谁new出来的对象谁来delete掉。
//模板类，是一个智能指针类，T代表指向的类型，必须为某个类型的子类型，
template <typename T>
class sp
{
public:
    typedef typename RefBase::weakref_type weakref_type;//起别名，
    
    inline sp() : m_ptr(0) { }

    sp(T* other);//这个构造函数将普通指针变为智能指针，，，
    sp(const sp<T>& other);//拷贝构造函数，
    template<typename U> sp(U* other);
    template<typename U> sp(const sp<U>& other);

    ~sp();
    
    // Assignment

    sp& operator = (T* other);//重载了=运算符，将普通指针转变为智能指针，
    sp& operator = (const sp<T>& other);//重载=运算符，智能指针之间进行赋值运算，，
    
    template<typename U> sp& operator = (const sp<U>& other);
    template<typename U> sp& operator = (U* other);
    
    //! Special optimization for use by ProcessState (and nobody else).
    void force_set(T* other);
    
    // Reset
    
    void clear();
    
    // Accessors

    inline  T&      operator* () const  { return *m_ptr; }//重载指针运算符，取出真正的对象，
    inline  T*      operator-> () const { return m_ptr;  }//重载箭头运算符，，，返回真正对象的指针，
    inline  T*      get() const         { return m_ptr; }

    // Operators
        
    COMPARE(==)
    COMPARE(!=)
    COMPARE(>)
    COMPARE(<)
    COMPARE(<=)
    COMPARE(>=)

private:    
    template<typename Y> friend class sp;
    template<typename Y> friend class wp;

    // Optimization for wp::promote().
    sp(T* p, weakref_type* refs);
    
    T*              m_ptr;//指向具体的类型
};

template <typename T>
TextOutput& operator<<(TextOutput& to, const sp<T>& val);

// ---------------------------------------------------------------------------
//弱指针，，，弱指针不能直接操作目标对象，，尽管它的成员变量里面有目标对象，
template <typename T>
class wp
{
public:
    typedef typename RefBase::weakref_type weakref_type;
    
    inline wp() : m_ptr(0) { }

    wp(T* other);//构造函数，普通指针变成弱指针
    wp(const wp<T>& other);//构造函数，
    wp(const sp<T>& other);//构造函数，强指针变成弱指针
    template<typename U> wp(U* other);
    template<typename U> wp(const sp<U>& other);
    template<typename U> wp(const wp<U>& other);

    ~wp();
    
    // Assignment

    wp& operator = (T* other);//重载=运算符，普通指针变成弱指针，
    wp& operator = (const wp<T>& other);//重载=运算符，弱指针之间赋值
    wp& operator = (const sp<T>& other);//重载=运算符，强指针赋值给弱指针，
    
    template<typename U> wp& operator = (U* other);
    template<typename U> wp& operator = (const wp<U>& other);
    template<typename U> wp& operator = (const sp<U>& other);
    
    void set_object_and_refs(T* other, weakref_type* refs);

    // promotion to sp
    
    sp<T> promote() const;

    // Reset
    
    void clear();

    // Accessors
    
    inline  weakref_type* get_refs() const { return m_refs; }
    
    inline  T* unsafe_get() const { return m_ptr; }

    // Operators

    COMPARE_WEAK(==)
    COMPARE_WEAK(!=)
    COMPARE_WEAK(>)
    COMPARE_WEAK(<)
    COMPARE_WEAK(<=)
    COMPARE_WEAK(>=)

    inline bool operator == (const wp<T>& o) const {
        return (m_ptr == o.m_ptr) && (m_refs == o.m_refs);
    }
    template<typename U>
    inline bool operator == (const wp<U>& o) const {
        return m_ptr == o.m_ptr;
    }

    inline bool operator > (const wp<T>& o) const {
        return (m_ptr == o.m_ptr) ? (m_refs > o.m_refs) : (m_ptr > o.m_ptr);
    }
    template<typename U>
    inline bool operator > (const wp<U>& o) const {
        return (m_ptr == o.m_ptr) ? (m_refs > o.m_refs) : (m_ptr > o.m_ptr);
    }

    inline bool operator < (const wp<T>& o) const {
        return (m_ptr == o.m_ptr) ? (m_refs < o.m_refs) : (m_ptr < o.m_ptr);
    }
    template<typename U>
    inline bool operator < (const wp<U>& o) const {
        return (m_ptr == o.m_ptr) ? (m_refs < o.m_refs) : (m_ptr < o.m_ptr);
    }
                         inline bool operator != (const wp<T>& o) const { return m_refs != o.m_refs; }
    template<typename U> inline bool operator != (const wp<U>& o) const { return !operator == (o); }
                         inline bool operator <= (const wp<T>& o) const { return !operator > (o); }
    template<typename U> inline bool operator <= (const wp<U>& o) const { return !operator > (o); }
                         inline bool operator >= (const wp<T>& o) const { return !operator < (o); }
    template<typename U> inline bool operator >= (const wp<U>& o) const { return !operator < (o); }

private:
    template<typename Y> friend class sp;
    template<typename Y> friend class wp;

    T*              m_ptr;//目标对象，，，
    weakref_type*   m_refs;
};

template <typename T>
TextOutput& operator<<(TextOutput& to, const wp<T>& val);

#undef COMPARE
#undef COMPARE_WEAK

// ---------------------------------------------------------------------------
// No user serviceable parts below here.
//强指针的构造函数，将普通指针转为强指针(智能指针的一种)
//
template<typename T>
sp<T>::sp(T* other)
    : m_ptr(other)
{
		//增加一个目标对象的引用计数，
    if (other) other->incStrong(this);
}
//拷贝构造函数，智能指针之间的赋值，
template<typename T>
sp<T>::sp(const sp<T>& other)
    : m_ptr(other.m_ptr)
{
		//增加一个目标对象的引用计数，
    if (m_ptr) m_ptr->incStrong(this);
}

template<typename T> template<typename U>
sp<T>::sp(U* other) : m_ptr(other)
{
    if (other) other->incStrong(this);
}

template<typename T> template<typename U>
sp<T>::sp(const sp<U>& other)
    : m_ptr(other.m_ptr)
{
    if (m_ptr) m_ptr->incStrong(this);
}
//析构函数，
template<typename T>
sp<T>::~sp()
{
		//析构函数，将目标对象的引用计数减掉1
    if (m_ptr) m_ptr->decStrong(this);
}
//重载=运算符，，进行深复制
template<typename T>
sp<T>& sp<T>::operator = (const sp<T>& other) {
    T* otherPtr(other.m_ptr);//这个不是普通的指针变量么，原来还可以这样用，
    
    
    if (otherPtr) otherPtr->incStrong(this);//因为想让当前智能指针指向目标对象，所以要增加引用计数
    	
    if (m_ptr) m_ptr->decStrong(this);//如果当前的智能指针原来指向了一个目标对象，因为下面要改变指向不同的目标对象，那么就不再引用之前的目标对象了。
    m_ptr = otherPtr;
    return *this;//原来还可以这样用，
}
//重载=运算符，普通指针转换为智能指针
template<typename T>
sp<T>& sp<T>::operator = (T* other)
{
    if (other) other->incStrong(this);//因为想让当前智能指针指向目标对象，所以要增加引用计数
    	
    if (m_ptr) m_ptr->decStrong(this);//如果当前的智能指针原来指向了一个目标对象，因为下面要改变指向不同的目标对象，那么就不再引用之前的目标对象了。
    m_ptr = other;
    return *this;
}

template<typename T> template<typename U>
sp<T>& sp<T>::operator = (const sp<U>& other)
{
    U* otherPtr(other.m_ptr);
    if (otherPtr) otherPtr->incStrong(this);
    if (m_ptr) m_ptr->decStrong(this);
    m_ptr = otherPtr;
    return *this;
}

template<typename T> template<typename U>
sp<T>& sp<T>::operator = (U* other)
{
    if (other) other->incStrong(this);
    if (m_ptr) m_ptr->decStrong(this);
    m_ptr = other;
    return *this;
}

template<typename T>    
void sp<T>::force_set(T* other)
{
    other->forceIncStrong(this);
    m_ptr = other;
}

template<typename T>
void sp<T>::clear()
{
    if (m_ptr) {
        m_ptr->decStrong(this);
        m_ptr = 0;
    }
}

template<typename T>
sp<T>::sp(T* p, weakref_type* refs)
    : m_ptr((p && refs->attemptIncStrong(this)) ? p : 0)
{
}

template <typename T>
inline TextOutput& operator<<(TextOutput& to, const sp<T>& val)
{
    to << "sp<>(" << val.get() << ")";
    return to;
}

// ---------------------------------------------------------------------------
//构造函数，，，
template<typename T>
wp<T>::wp(T* other)
    : m_ptr(other)
{
    if (other) m_refs = other->createWeak(this);
}

template<typename T>
wp<T>::wp(const wp<T>& other)
    : m_ptr(other.m_ptr), m_refs(other.m_refs)
{
    if (m_ptr) m_refs->incWeak(this);
}

template<typename T>
wp<T>::wp(const sp<T>& other)
    : m_ptr(other.m_ptr)
{
    if (m_ptr) {
        m_refs = m_ptr->createWeak(this);
    }
}

template<typename T> template<typename U>
wp<T>::wp(U* other)
    : m_ptr(other)
{
    if (other) m_refs = other->createWeak(this);
}

template<typename T> template<typename U>
wp<T>::wp(const wp<U>& other)
    : m_ptr(other.m_ptr)
{
    if (m_ptr) {
        m_refs = other.m_refs;
        m_refs->incWeak(this);
    }
}

template<typename T> template<typename U>
wp<T>::wp(const sp<U>& other)
    : m_ptr(other.m_ptr)
{
    if (m_ptr) {
        m_refs = m_ptr->createWeak(this);
    }
}

template<typename T>
wp<T>::~wp()
{
    if (m_ptr) m_refs->decWeak(this);
}

template<typename T>
wp<T>& wp<T>::operator = (T* other)
{
    weakref_type* newRefs =
        other ? other->createWeak(this) : 0;
    if (m_ptr) m_refs->decWeak(this);
    m_ptr = other;
    m_refs = newRefs;
    return *this;
}

template<typename T>
wp<T>& wp<T>::operator = (const wp<T>& other)
{
    weakref_type* otherRefs(other.m_refs);
    T* otherPtr(other.m_ptr);
    if (otherPtr) otherRefs->incWeak(this);
    if (m_ptr) m_refs->decWeak(this);
    m_ptr = otherPtr;
    m_refs = otherRefs;
    return *this;
}

template<typename T>
wp<T>& wp<T>::operator = (const sp<T>& other)
{
    weakref_type* newRefs =
        other != NULL ? other->createWeak(this) : 0;
    T* otherPtr(other.m_ptr);
    if (m_ptr) m_refs->decWeak(this);
    m_ptr = otherPtr;
    m_refs = newRefs;
    return *this;
}

template<typename T> template<typename U>
wp<T>& wp<T>::operator = (U* other)
{
    weakref_type* newRefs =
        other ? other->createWeak(this) : 0;
    if (m_ptr) m_refs->decWeak(this);
    m_ptr = other;
    m_refs = newRefs;
    return *this;
}

template<typename T> template<typename U>
wp<T>& wp<T>::operator = (const wp<U>& other)
{
    weakref_type* otherRefs(other.m_refs);
    U* otherPtr(other.m_ptr);
    if (otherPtr) otherRefs->incWeak(this);
    if (m_ptr) m_refs->decWeak(this);
    m_ptr = otherPtr;
    m_refs = otherRefs;
    return *this;
}

template<typename T> template<typename U>
wp<T>& wp<T>::operator = (const sp<U>& other)
{
    weakref_type* newRefs =
        other != NULL ? other->createWeak(this) : 0;
    U* otherPtr(other.m_ptr);
    if (m_ptr) m_refs->decWeak(this);
    m_ptr = otherPtr;
    m_refs = newRefs;
    return *this;
}

template<typename T>
void wp<T>::set_object_and_refs(T* other, weakref_type* refs)
{
    if (other) refs->incWeak(this);
    if (m_ptr) m_refs->decWeak(this);
    m_ptr = other;
    m_refs = refs;
}
//若指针升级为强指针，通过重新生成强指针对象，，，
template<typename T>
sp<T> wp<T>::promote() const
{
    return sp<T>(m_ptr, m_refs);
}

template<typename T>
void wp<T>::clear()
{
    if (m_ptr) {
        m_refs->decWeak(this);
        m_ptr = 0;
    }
}

template <typename T>
inline TextOutput& operator<<(TextOutput& to, const wp<T>& val)
{
    to << "wp<>(" << val.unsafe_get() << ")";
    return to;
}

}; // namespace android

// ---------------------------------------------------------------------------

#endif // ANDROID_REF_BASE_H
