package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        if (id == null) {
            return Result.fail("参数异常");
        }
        Blog blog = query().eq("id", id).one();
        if (blog == null) {
            return Result.fail("该博客不存在");
        }

        queryBlogUser(blog);
        queryBlogLiked(blog);

        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlogs(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            queryBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result blogLikes(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            boolean success = update().setSql("liked = liked+1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            boolean success = update().setSql("liked = liked-1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        //查询点赞前五的用户信息
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //判空
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //转换id类型
        List<Long> ids  = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idstr = StrUtil.join(",", ids);
        //根据id查询用户信息DTO
        List<UserDTO> userDtoList = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id,"+idstr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());


        return Result.ok(userDtoList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }

        List<Follow> followUsers = followService.query().eq("follow_user_id", user.getId()).list();

        for (Follow follow : followUsers) {

            Long userId = follow.getUserId();
            String key = "feed:"+ userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Integer offset, Long max) {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();

        //2. 查询收件箱
        String key = "feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3. 判断是否为空
        if (typedTuples == null ||typedTuples.isEmpty()) {
            return Result.ok();
        }
        //4. 解析数据 blogId mintime(时间戳) offet
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long mintime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            //4.1 获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            //4.2 获取时间戳
            long time = typedTuple.getScore().longValue();
            // score重复
            if (time == mintime){
                os+=1; //offset自增
            }else {//刷新最小score os重置
                mintime = time;
                os = 1;
            }
        }
        //5. 根据id查询blog
        String idStr = StrUtil.join(",",ids);

        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list();

        for (Blog blog : blogs){

            //查询blog相关用户
            queryBlogUser(blog);
            //查询blog是否被当前用户点赞
            queryBlogLiked(blog);
        }

        //6. 判断 max是否与mintime相同
        if (max == mintime){
            os += offset;
        }
        //7. 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(mintime);
        r.setOffset(os);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

    }

    private void queryBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return ;
        }
        Long userId = user.getId();
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);

    }
}
